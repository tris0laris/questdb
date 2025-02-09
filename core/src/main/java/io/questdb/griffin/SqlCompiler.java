/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.*;
import io.questdb.cutlass.text.Atomicity;
import io.questdb.cutlass.text.TextException;
import io.questdb.cutlass.text.TextLoader;
import io.questdb.griffin.model.*;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.ServiceLoader;


public class SqlCompiler implements Closeable {
    public static final ObjList<String> sqlControlSymbols = new ObjList<>(8);
    private final static Log LOG = LogFactory.getLog(SqlCompiler.class);
    private static final IntList castGroups = new IntList();

    static {
        castGroups.extendAndSet(ColumnType.BOOLEAN, 2);
        castGroups.extendAndSet(ColumnType.BYTE, 1);
        castGroups.extendAndSet(ColumnType.SHORT, 1);
        castGroups.extendAndSet(ColumnType.CHAR, 1);
        castGroups.extendAndSet(ColumnType.INT, 1);
        castGroups.extendAndSet(ColumnType.LONG, 1);
        castGroups.extendAndSet(ColumnType.FLOAT, 1);
        castGroups.extendAndSet(ColumnType.DOUBLE, 1);
        castGroups.extendAndSet(ColumnType.DATE, 1);
        castGroups.extendAndSet(ColumnType.TIMESTAMP, 1);
        castGroups.extendAndSet(ColumnType.STRING, 3);
        castGroups.extendAndSet(ColumnType.SYMBOL, 3);
        castGroups.extendAndSet(ColumnType.BINARY, 4);

        sqlControlSymbols.add("(");
        sqlControlSymbols.add(";");
        sqlControlSymbols.add(")");
        sqlControlSymbols.add(",");
        sqlControlSymbols.add("/*");
        sqlControlSymbols.add("*/");
        sqlControlSymbols.add("--");
    }

    private final SqlOptimiser optimiser;
    private final SqlParser parser;
    private final ObjectPool<ExpressionNode> sqlNodePool;
    private final CharacterStore characterStore;
    private final ObjectPool<QueryColumn> queryColumnPool;
    private final ObjectPool<QueryModel> queryModelPool;
    private final GenericLexer lexer;
    private final SqlCodeGenerator codeGenerator;
    private final CairoConfiguration configuration;
    private final Path path = new Path();
    private final AppendMemory mem = new AppendMemory();
    private final BytecodeAssembler asm = new BytecodeAssembler();
    private final CairoWorkScheduler workScheduler;
    private final CairoEngine engine;
    private final ListColumnFilter listColumnFilter = new ListColumnFilter();
    private final EntityColumnFilter entityColumnFilter = new EntityColumnFilter();
    private final IntIntHashMap typeCast = new IntIntHashMap();
    private final ObjList<TableWriter> tableWriters = new ObjList<>();
    private final TableStructureAdapter tableStructureAdapter = new TableStructureAdapter();
    private final FunctionParser functionParser;
    private final CharSequenceObjHashMap<KeywordBasedExecutor> keywordBasedExecutors = new CharSequenceObjHashMap<>();
    private final CompiledQueryImpl compiledQuery = new CompiledQueryImpl();
    private final ExecutableMethod insertAsSelectMethod = this::insertAsSelect;
    private final ExecutableMethod createTableMethod = this::createTable;
    private final TextLoader textLoader;
    private final FilesFacade ff;

    public SqlCompiler(CairoEngine engine) {
        this(engine, null);
    }

    public SqlCompiler(CairoEngine engine, @Nullable CairoWorkScheduler workScheduler) {
        this.engine = engine;
        this.configuration = engine.getConfiguration();
        this.ff = configuration.getFilesFacade();
        this.workScheduler = workScheduler;
        this.sqlNodePool = new ObjectPool<>(ExpressionNode.FACTORY, configuration.getSqlExpressionPoolCapacity());
        this.queryColumnPool = new ObjectPool<>(QueryColumn.FACTORY, configuration.getSqlColumnPoolCapacity());
        this.queryModelPool = new ObjectPool<>(QueryModel.FACTORY, configuration.getSqlModelPoolCapacity());
        this.characterStore = new CharacterStore(
                configuration.getSqlCharacterStoreCapacity(),
                configuration.getSqlCharacterStoreSequencePoolCapacity()
        );
        this.lexer = new GenericLexer(configuration.getSqlLexerPoolCapacity());
        this.functionParser = new FunctionParser(configuration, ServiceLoader.load(FunctionFactory.class));
        this.codeGenerator = new SqlCodeGenerator(engine, configuration, functionParser);

        // we have cyclical dependency here
        functionParser.setSqlCodeGenerator(codeGenerator);

        keywordBasedExecutors.put("truncate", this::truncateTables);
        keywordBasedExecutors.put("TRUNCATE", this::truncateTables);
        keywordBasedExecutors.put("alter", this::alterTable);
        keywordBasedExecutors.put("ALTER", this::alterTable);
        keywordBasedExecutors.put("repair", this::repairTables);
        keywordBasedExecutors.put("REPAIR", this::repairTables);
        keywordBasedExecutors.put("set", this::compileSet);
        keywordBasedExecutors.put("SET", this::compileSet);
        keywordBasedExecutors.put("drop", this::dropTable);
        keywordBasedExecutors.put("DROP", this::dropTable);

        configureLexer(lexer);

        final PostOrderTreeTraversalAlgo postOrderTreeTraversalAlgo = new PostOrderTreeTraversalAlgo();
        optimiser = new SqlOptimiser(
                configuration,
                engine,
                characterStore,
                sqlNodePool,
                queryColumnPool,
                queryModelPool,
                postOrderTreeTraversalAlgo,
                functionParser,
                path
        );

        parser = new SqlParser(
                configuration,
                optimiser,
                characterStore,
                sqlNodePool,
                queryColumnPool,
                queryModelPool,
                postOrderTreeTraversalAlgo
        );

        this.textLoader = new TextLoader(engine);
    }

    public static void configureLexer(GenericLexer lexer) {
        for (int i = 0, k = sqlControlSymbols.size(); i < k; i++) {
            lexer.defineSymbol(sqlControlSymbols.getQuick(i));
        }
        for (int i = 0, k = OperatorExpression.operators.size(); i < k; i++) {
            OperatorExpression op = OperatorExpression.operators.getQuick(i);
            if (op.symbol) {
                lexer.defineSymbol(op.token);
            }
        }
    }

    // Creates data type converter.
    // INT and LONG NaN values are cast to their representation rather than Double or Float NaN.
    private static RecordToRowCopier assembleRecordToRowCopier(BytecodeAssembler asm, ColumnTypes from, RecordMetadata to, ColumnFilter toColumnFilter) {
        int timestampIndex = to.getTimestampIndex();
        asm.init(RecordToRowCopier.class);
        asm.setupPool();
        int thisClassIndex = asm.poolClass(asm.poolUtf8("questdbasm"));
        int interfaceClassIndex = asm.poolClass(RecordToRowCopier.class);

        int rGetInt = asm.poolInterfaceMethod(Record.class, "getInt", "(I)I");
        int rGetLong = asm.poolInterfaceMethod(Record.class, "getLong", "(I)J");
        int rGetLong256 = asm.poolInterfaceMethod(Record.class, "getLong256A", "(I)Lio/questdb/std/Long256;");
        int rGetDate = asm.poolInterfaceMethod(Record.class, "getDate", "(I)J");
        int rGetTimestamp = asm.poolInterfaceMethod(Record.class, "getTimestamp", "(I)J");
        //
        int rGetByte = asm.poolInterfaceMethod(Record.class, "getByte", "(I)B");
        int rGetShort = asm.poolInterfaceMethod(Record.class, "getShort", "(I)S");
        int rGetChar = asm.poolInterfaceMethod(Record.class, "getChar", "(I)C");
        int rGetBool = asm.poolInterfaceMethod(Record.class, "getBool", "(I)Z");
        int rGetFloat = asm.poolInterfaceMethod(Record.class, "getFloat", "(I)F");
        int rGetDouble = asm.poolInterfaceMethod(Record.class, "getDouble", "(I)D");
        int rGetSym = asm.poolInterfaceMethod(Record.class, "getSym", "(I)Ljava/lang/CharSequence;");
        int rGetStr = asm.poolInterfaceMethod(Record.class, "getStr", "(I)Ljava/lang/CharSequence;");
        int rGetBin = asm.poolInterfaceMethod(Record.class, "getBin", "(I)Lio/questdb/std/BinarySequence;");
        //
        int wPutInt = asm.poolMethod(TableWriter.Row.class, "putInt", "(II)V");
        int wPutLong = asm.poolMethod(TableWriter.Row.class, "putLong", "(IJ)V");
        int wPutLong256 = asm.poolMethod(TableWriter.Row.class, "putLong256", "(ILio/questdb/std/Long256;)V");
        int wPutDate = asm.poolMethod(TableWriter.Row.class, "putDate", "(IJ)V");
        int wPutTimestamp = asm.poolMethod(TableWriter.Row.class, "putTimestamp", "(IJ)V");
        //
        int wPutByte = asm.poolMethod(TableWriter.Row.class, "putByte", "(IB)V");
        int wPutShort = asm.poolMethod(TableWriter.Row.class, "putShort", "(IS)V");
        int wPutBool = asm.poolMethod(TableWriter.Row.class, "putBool", "(IZ)V");
        int wPutFloat = asm.poolMethod(TableWriter.Row.class, "putFloat", "(IF)V");
        int wPutDouble = asm.poolMethod(TableWriter.Row.class, "putDouble", "(ID)V");
        int wPutSym = asm.poolMethod(TableWriter.Row.class, "putSym", "(ILjava/lang/CharSequence;)V");
        int wPutStr = asm.poolMethod(TableWriter.Row.class, "putStr", "(ILjava/lang/CharSequence;)V");
        int wPutStrChar = asm.poolMethod(TableWriter.Row.class, "putStr", "(IC)V");
        int wPutChar = asm.poolMethod(TableWriter.Row.class, "putChar", "(IC)V");
        int wPutBin = asm.poolMethod(TableWriter.Row.class, "putBin", "(ILio/questdb/std/BinarySequence;)V");

        int copyNameIndex = asm.poolUtf8("copy");
        int copySigIndex = asm.poolUtf8("(Lio/questdb/cairo/sql/Record;Lio/questdb/cairo/TableWriter$Row;)V");

        asm.finishPool();
        asm.defineClass(thisClassIndex);
        asm.interfaceCount(1);
        asm.putShort(interfaceClassIndex);
        asm.fieldCount(0);
        asm.methodCount(2);
        asm.defineDefaultConstructor();

        asm.startMethod(copyNameIndex, copySigIndex, 4, 3);

        int n = toColumnFilter.getColumnCount();
        for (int i = 0; i < n; i++) {

            final int toColumnIndex = toColumnFilter.getColumnIndex(i);
            // do not copy timestamp, it will be copied externally to this helper

            if (toColumnIndex == timestampIndex) {
                continue;
            }

            asm.aload(2);
            asm.iconst(toColumnIndex);
            asm.aload(1);
            asm.iconst(i);


            switch (from.getColumnType(i)) {
                case ColumnType.INT:
                    asm.invokeInterface(rGetInt, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.LONG:
                            asm.i2l();
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.DATE:
                            asm.i2l();
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.i2l();
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.i2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.i2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutInt);
                            break;
                    }
                    break;
                case ColumnType.LONG:
                    asm.invokeInterface(rGetLong, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.l2i();
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.DATE:
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.l2i();
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.l2i();
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.l2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.l2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutLong);
                            break;
                    }
                    break;
                case ColumnType.DATE:
                    asm.invokeInterface(rGetDate, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.l2i();
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.l2i();
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.l2i();
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.l2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.l2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutDate);
                            break;
                    }
                    break;
                case ColumnType.TIMESTAMP:
                    asm.invokeInterface(rGetTimestamp, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.l2i();
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.SHORT:
                            asm.l2i();
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.l2i();
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.l2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.l2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        case ColumnType.DATE:
                            asm.invokeVirtual(wPutDate);
                            break;
                        default:
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                    }
                    break;
                case ColumnType.BYTE:
                    asm.invokeInterface(rGetByte, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.i2l();
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.DATE:
                            asm.i2l();
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.i2l();
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.FLOAT:
                            asm.i2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.i2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutByte);
                            break;
                    }
                    break;
                case ColumnType.SHORT:
                    asm.invokeInterface(rGetShort, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.i2l();
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.DATE:
                            asm.i2l();
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.i2l();
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.BYTE:
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.i2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        case ColumnType.DOUBLE:
                            asm.i2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutShort);
                            break;
                    }
                    break;
                case ColumnType.BOOLEAN:
                    asm.invokeInterface(rGetBool, 1);
                    asm.invokeVirtual(wPutBool);
                    break;
                case ColumnType.FLOAT:
                    asm.invokeInterface(rGetFloat, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.f2i();
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.f2l();
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.DATE:
                            asm.f2l();
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.f2l();
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.f2i();
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.f2i();
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.DOUBLE:
                            asm.f2d();
                            asm.invokeVirtual(wPutDouble);
                            break;
                        default:
                            asm.invokeVirtual(wPutFloat);
                            break;
                    }
                    break;
                case ColumnType.DOUBLE:
                    asm.invokeInterface(rGetDouble, 1);
                    switch (to.getColumnType(toColumnIndex)) {
                        case ColumnType.INT:
                            asm.d2i();
                            asm.invokeVirtual(wPutInt);
                            break;
                        case ColumnType.LONG:
                            asm.d2l();
                            asm.invokeVirtual(wPutLong);
                            break;
                        case ColumnType.DATE:
                            asm.d2l();
                            asm.invokeVirtual(wPutDate);
                            break;
                        case ColumnType.TIMESTAMP:
                            asm.d2l();
                            asm.invokeVirtual(wPutTimestamp);
                            break;
                        case ColumnType.SHORT:
                            asm.d2i();
                            asm.i2s();
                            asm.invokeVirtual(wPutShort);
                            break;
                        case ColumnType.BYTE:
                            asm.d2i();
                            asm.i2b();
                            asm.invokeVirtual(wPutByte);
                            break;
                        case ColumnType.FLOAT:
                            asm.d2f();
                            asm.invokeVirtual(wPutFloat);
                            break;
                        default:
                            asm.invokeVirtual(wPutDouble);
                            break;
                    }
                    break;
                case ColumnType.CHAR:
                    asm.invokeInterface(rGetChar, 1);
                    if (to.getColumnType(toColumnIndex) == ColumnType.STRING) {
                        asm.invokeVirtual(wPutStrChar);
                    } else {
                        asm.invokeVirtual(wPutChar);
                    }
                    break;
                case ColumnType.SYMBOL:
                    asm.invokeInterface(rGetSym, 1);
                    if (to.getColumnType(toColumnIndex) == ColumnType.STRING) {
                        asm.invokeVirtual(wPutStr);
                    } else {
                        asm.invokeVirtual(wPutSym);
                    }
                    break;
                case ColumnType.STRING:
                    asm.invokeInterface(rGetStr, 1);
                    if (to.getColumnType(toColumnIndex) == ColumnType.SYMBOL) {
                        asm.invokeVirtual(wPutSym);
                    } else {
                        asm.invokeVirtual(wPutStr);
                    }
                    break;
                case ColumnType.BINARY:
                    asm.invokeInterface(rGetBin, 1);
                    asm.invokeVirtual(wPutBin);
                    break;
                case ColumnType.LONG256:
                    asm.invokeInterface(rGetLong256, 1);
                    asm.invokeVirtual(wPutLong256);
                    break;
                default:
                    break;
            }
        }

        asm.return_();
        asm.endMethodCode();

        // exceptions
        asm.putShort(0);

        // we have to add stack map table as branch target
        // jvm requires it

        // attributes: 0 (void, no stack verification)
        asm.putShort(0);

        asm.endMethod();

        // class attribute count
        asm.putShort(0);

        return asm.newInstance();
    }

    private static boolean isCompatibleCase(int from, int to) {
        return castGroups.getQuick(from) == castGroups.getQuick(to);
    }

    private static boolean isAssignableFrom(int to, int from) {
        return to == from
                || (
                from >= ColumnType.BYTE
                        && to >= ColumnType.BYTE
                        && to <= ColumnType.DOUBLE
                        && from < to)
                || (from == ColumnType.STRING && to == ColumnType.SYMBOL)
                || (from == ColumnType.SYMBOL && to == ColumnType.STRING)
                || (from == ColumnType.CHAR && to == ColumnType.STRING);
    }

    private static void expectKeyword(GenericLexer lexer, CharSequence keyword) throws SqlException {
        CharSequence tok = SqlUtil.fetchNext(lexer);

        if (tok == null) {
            throw SqlException.position(lexer.getPosition()).put('\'').put(keyword).put("' expected");
        }

        if (!Chars.equalsLowerCaseAscii(tok, keyword)) {
            throw SqlException.position(lexer.lastTokenPosition()).put('\'').put(keyword).put("' expected");
        }
    }

    private static CharSequence expectToken(GenericLexer lexer, CharSequence expected) throws SqlException {
        CharSequence tok = SqlUtil.fetchNext(lexer);

        if (tok == null) {
            throw SqlException.position(lexer.getPosition()).put(expected).put(" expected");
        }

        return tok;
    }

    @Override
    public void close() {
        Misc.free(path);
        Misc.free(textLoader);
    }

    public CompiledQuery compile(CharSequence query) throws SqlException {
        return compile(query, DefaultSqlExecutionContext.INSTANCE);
    }

    @NotNull
    public CompiledQuery compile(@NotNull CharSequence query, @NotNull SqlExecutionContext executionContext) throws SqlException {
        clear();
        //
        // these are quick executions that do not require building of a model
        //
        lexer.of(query);

        final CharSequence tok = SqlUtil.fetchNext(lexer);
        final KeywordBasedExecutor executor = keywordBasedExecutors.get(tok);
        if (executor == null) {
            return compileUsingModel(executionContext);
        }
        return executor.execute(executionContext);
    }

    public CairoEngine getEngine() {
        return engine;
    }

    private CompiledQuery alterTable(SqlExecutionContext executionContext) throws SqlException {
        CharSequence tok;
        expectKeyword(lexer, "table");

        final int tableNamePosition = lexer.getPosition();

        tok = GenericLexer.unquote(expectToken(lexer, "table name"));

        tableExistsOrFail(tableNamePosition, tok, executionContext);

        CharSequence tableName = GenericLexer.immutableOf(tok);
        try (TableWriter writer = engine.getWriter(executionContext.getCairoSecurityContext(), tableName)) {

            tok = expectToken(lexer, "'add' or 'drop'");

            if (Chars.equalsLowerCaseAscii("add", tok)) {
                alterTableAddColumn(tableNamePosition, writer);
            } else if (Chars.equalsLowerCaseAscii("drop", tok)) {
                alterTableDropColumn(tableNamePosition, writer);
            } else {
                throw SqlException.$(lexer.lastTokenPosition(), "unexpected token: ").put(tok);
            }
        } catch (CairoException e) {
            LOG.info().$("failed to lock table for alter: ").$((Sinkable) e).$();
            throw SqlException.$(tableNamePosition, "table '").put(tableName).put("' is busy");
        }

        return compiledQuery.ofAlter();
    }

    private void alterTableAddColumn(int tableNamePosition, TableWriter writer) throws SqlException {
        // add columns to table
        expectKeyword(lexer, "column");

        do {
            CharSequence tok = expectToken(lexer, "column name");

            int index = writer.getMetadata().getColumnIndexQuiet(tok);
            if (index != -1) {
                throw SqlException.$(lexer.lastTokenPosition(), "column '").put(tok).put("' already exists");
            }

            CharSequence columnName = GenericLexer.immutableOf(tok);

            tok = expectToken(lexer, "column type");

            int type = ColumnType.columnTypeOf(tok);
            if (type == -1) {
                throw SqlException.$(lexer.lastTokenPosition(), "invalid type");
            }

            tok = SqlUtil.fetchNext(lexer);

            final int indexValueBlockCapacity;
            final boolean cache;
            int symbolCapacity;
            final boolean indexed;

            if (type == ColumnType.SYMBOL && tok != null && !Chars.equals(tok, ',')) {

                if (Chars.equalsLowerCaseAscii(tok, "capacity")) {
                    tok = expectToken(lexer, "symbol capacity");

                    final boolean negative;
                    final int errorPos = lexer.lastTokenPosition();
                    if (Chars.equals(tok, '-')) {
                        negative = true;
                        tok = expectToken(lexer, "symbol capacity");
                    } else {
                        negative = false;
                    }

                    try {
                        symbolCapacity = Numbers.parseInt(tok);
                    } catch (NumericException e) {
                        throw SqlException.$(lexer.lastTokenPosition(), "numeric capacity expected");
                    }

                    if (negative) {
                        symbolCapacity = -symbolCapacity;
                    }

                    TableUtils.validateSymbolCapacity(errorPos, symbolCapacity);

                    tok = SqlUtil.fetchNext(lexer);
                } else {
                    symbolCapacity = configuration.getDefaultSymbolCapacity();
                }


                if (Chars.equalsLowerCaseAsciiNc(tok, "cache")) {
                    cache = true;
                    tok = SqlUtil.fetchNext(lexer);
                } else if (Chars.equalsLowerCaseAsciiNc(tok, "nocache")) {
                    cache = false;
                    tok = SqlUtil.fetchNext(lexer);
                } else {
                    cache = configuration.getDefaultSymbolCacheFlag();
                }

                TableUtils.validateSymbolCapacityCached(cache, symbolCapacity, lexer.lastTokenPosition());

                indexed = Chars.equalsLowerCaseAsciiNc(tok, "index");
                if (indexed) {
                    tok = SqlUtil.fetchNext(lexer);
                }

                if (Chars.equalsLowerCaseAsciiNc(tok, "capacity")) {
                    tok = expectToken(lexer, "symbol index capacity");

                    try {
                        indexValueBlockCapacity = Numbers.parseInt(tok);
                    } catch (NumericException e) {
                        throw SqlException.$(lexer.lastTokenPosition(), "numeric capacity expected");
                    }
                    tok = SqlUtil.fetchNext(lexer);
                } else {
                    indexValueBlockCapacity = configuration.getIndexValueBlockSize();
                }
            } else {
                cache = false;
                indexValueBlockCapacity = configuration.getIndexValueBlockSize();
                symbolCapacity = configuration.getDefaultSymbolCapacity();
                indexed = false;
            }

            try {
                writer.addColumn(
                        columnName,
                        type,
                        Numbers.ceilPow2(symbolCapacity),
                        cache, indexed,
                        Numbers.ceilPow2(indexValueBlockCapacity)
                );
            } catch (CairoException e) {
                LOG.error().$("Cannot add column '").$(writer.getName()).$('.').$(columnName).$("'. Exception: ").$((Sinkable) e).$();
                throw SqlException.$(tableNamePosition, "Cannot add column [error=").put(e.getFlyweightMessage()).put(']');
            }

            if (tok == null) {
                break;
            }

            if (!Chars.equals(tok, ',')) {
                throw SqlException.$(lexer.lastTokenPosition(), "',' expected");
            }

        } while (true);
    }

    private void alterTableDropColumn(int tableNamePosition, TableWriter writer) throws SqlException {
        // add columns to table
        expectKeyword(lexer, "column");

        RecordMetadata metadata = writer.getMetadata();

        do {
            CharSequence tok = expectToken(lexer, "column name");

            if (metadata.getColumnIndexQuiet(tok) == -1) {
                throw SqlException.invalidColumn(lexer.lastTokenPosition(), tok);
            }

            try {
                writer.removeColumn(tok);
            } catch (CairoException e) {
                LOG.error().$("Cannot drop column '").$(writer.getName()).$('.').$(tok).$("'. Exception: ").$((Sinkable) e).$();
                throw SqlException.$(tableNamePosition, "Cannot add column. Try again later.");
            }

            tok = SqlUtil.fetchNext(lexer);

            if (tok == null) {
                break;
            }

            if (!Chars.equals(tok, ',')) {
                throw SqlException.$(lexer.lastTokenPosition(), "',' expected");
            }
        } while (true);
    }

    private void clear() {
        sqlNodePool.clear();
        characterStore.clear();
        queryColumnPool.clear();
        queryModelPool.clear();
        optimiser.clear();
        parser.clear();
    }

    private ExecutionModel compileExecutionModel(SqlExecutionContext executionContext) throws SqlException {
        ExecutionModel model = parser.parse(lexer, executionContext);
        switch (model.getModelType()) {
            case ExecutionModel.QUERY:
                return optimiser.optimise((QueryModel) model, executionContext);
            case ExecutionModel.INSERT:
                InsertModel insertModel = (InsertModel) model;
                if (insertModel.getQueryModel() != null) {
                    return validateAndOptimiseInsertAsSelect(insertModel, executionContext);
                } else {
                    return lightlyValidateInsertModel(insertModel);
                }
            default:
                return model;
        }
    }

    private CompiledQuery compileSet(SqlExecutionContext executionContext) {
        return compiledQuery.ofSet();
    }

    @NotNull
    private CompiledQuery compileUsingModel(SqlExecutionContext executionContext) throws SqlException {
        // This method will not populate sql cache directly;
        // factories are assumed to be non reentrant and once
        // factory is out of this method the caller assumes
        // full ownership over it. In that however caller may
        // chose to return factory back to this or any other
        // instance of compiler for safekeeping

        // lexer would have parsed first token to determine direction of execution flow
        lexer.unparse();

        ExecutionModel executionModel = compileExecutionModel(executionContext);
        switch (executionModel.getModelType()) {
            case ExecutionModel.QUERY:
                return compiledQuery.of(generate((QueryModel) executionModel, executionContext));
            case ExecutionModel.CREATE_TABLE:
                return createTableWithRetries(executionModel, executionContext);
            case ExecutionModel.COPY:
                return executeCopy(executionContext, (CopyModel) executionModel);
            default:
                InsertModel insertModel = (InsertModel) executionModel;
                if (insertModel.getQueryModel() != null) {
                    return executeWithRetries(
                            insertAsSelectMethod,
                            executionModel,
                            configuration.getCreateAsSelectRetryCount(),
                            executionContext
                    );
                }
                return insert(executionModel, executionContext);
        }
    }

    private void copyOrdered(TableWriter writer, RecordCursor cursor, RecordToRowCopier copier, int cursorTimestampIndex) {
        final Record record = cursor.getRecord();
        while (cursor.hasNext()) {
            TableWriter.Row row = writer.newRow(record.getTimestamp(cursorTimestampIndex));
            copier.copy(record, row);
            row.append();
        }
        writer.commit();
    }

    private void copyTable(SqlExecutionContext executionContext, CopyModel model) throws SqlException {
        try {
            int len = configuration.getSqlCopyBufferSize();
            long buf = Unsafe.malloc(len);
            try {
                path.of(GenericLexer.unquote(model.getFileName().token)).$();
                long fd = ff.openRO(path);
                if (fd == -1) {
                    throw SqlException.$(model.getFileName().position, "could not open file [errno=").put(Os.errno()).put(']');
                }
                long fileLen = ff.length(fd);
                long n = ff.read(fd, buf, len, 0);
                if (n > 0) {
                    textLoader.parse(buf, buf + n, executionContext.getCairoSecurityContext());
                    textLoader.setState(TextLoader.LOAD_DATA);
                    int read;
                    while (n < fileLen) {
                        read = (int) ff.read(fd, buf, len, n);
                        if (read < 1) {
                            throw SqlException.$(model.getFileName().position, "could not read file [errno=").put(ff.errno()).put(']');
                        }
                        textLoader.parse(buf, buf + read, executionContext.getCairoSecurityContext());
                        n += read;
                    }
                    textLoader.wrapUp();
                }
            } finally {
                Unsafe.free(buf, len);
            }
        } catch (TextException e) {
            // we do not expect JSON exception here
        } finally {
            LOG.info().$("copied").$();
        }
    }

    private TableWriter copyTableData(CharSequence tableName, RecordCursor cursor, RecordMetadata cursorMetadata) {
        TableWriter writer = new TableWriter(configuration, tableName, workScheduler, false, DefaultLifecycleManager.INSTANCE);
        try {
            RecordMetadata writerMetadata = writer.getMetadata();
            entityColumnFilter.of(writerMetadata.getColumnCount());
            RecordToRowCopier recordToRowCopier = assembleRecordToRowCopier(asm, cursorMetadata, writerMetadata, entityColumnFilter);

            int timestampIndex = writerMetadata.getTimestampIndex();
            if (timestampIndex == -1) {
                copyUnordered(cursor, writer, recordToRowCopier);
            } else {
                copyOrdered(writer, cursor, recordToRowCopier, timestampIndex);
            }
            return writer;
        } catch (CairoException e) {
            writer.close();
            throw e;
        }
    }

    private void copyUnordered(RecordCursor cursor, TableWriter writer, RecordToRowCopier ccopier) {
        final Record record = cursor.getRecord();
        while (cursor.hasNext()) {
            TableWriter.Row row = writer.newRow();
            ccopier.copy(record, row);
            row.append();
        }
        writer.commit();
    }

    private CompiledQuery createTable(final ExecutionModel model, SqlExecutionContext executionContext) throws SqlException {
        final CreateTableModel createTableModel = (CreateTableModel) model;
        final ExpressionNode name = createTableModel.getName();


        if (engine.lock(executionContext.getCairoSecurityContext(), name.token)) {
            TableWriter writer = null;

            try {
                if (engine.getStatus(
                        executionContext.getCairoSecurityContext(),
                        path,
                        name.token
                ) != TableUtils.TABLE_DOES_NOT_EXIST) {
                    throw SqlException.$(name.position, "table already exists");
                }

                try {
                    if (createTableModel.getQueryModel() == null) {
                        engine.creatTable(executionContext.getCairoSecurityContext(), mem, path, createTableModel);
                    } else {
                        writer = createTableFromCursor(createTableModel, executionContext);
                    }
                } catch (CairoException e) {
                    LOG.error().$("could not create table [error=").$((Sinkable) e).$(']').$();
                    throw SqlException.$(name.position, "Could not create table. See log for details.");
                }
            } finally {
                engine.unlock(executionContext.getCairoSecurityContext(), name.token, writer);
            }
        } else {
            throw SqlException.$(name.position, "cannot acquire table lock");
        }

        return compiledQuery.ofCreateTable();
    }

    private TableWriter createTableFromCursor(CreateTableModel model, SqlExecutionContext executionContext) throws SqlException {
        try (final RecordCursorFactory factory = generate(model.getQueryModel(), executionContext);
             final RecordCursor cursor = factory.getCursor(executionContext)
        ) {
            typeCast.clear();
            final RecordMetadata metadata = factory.getMetadata();
            validateTableModelAndCreateTypeCast(model, metadata, typeCast);
            engine.creatTable(
                    executionContext.getCairoSecurityContext(),
                    mem,
                    path,
                    tableStructureAdapter.of(model, metadata, typeCast)
            );

            try {
                return copyTableData(model.getName().token, cursor, metadata);
            } catch (CairoException e) {
                if (removeTableDirectory(model)) {
                    throw e;
                }
                throw SqlException.$(0, "Concurrent modification cannot be handled. Failed to clean up. See log for more details.");
            }
        }
    }

    /**
     * Creates new table.
     * <p>
     * Table name must not exist. Existence check relies on directory existence followed by attempt to clarify what
     * that directory is. Sometimes it can be just empty directory, which prevents new table from being created.
     * <p>
     * Table name can be utf8 encoded but must not contain '.' (dot). Dot is used to separate table and field name,
     * where table is uses as an alias.
     * <p>
     * Creating table from column definition looks like:
     * <code>
     * create table x (column_name column_type, ...) [timestamp(column_name)] [partition by ...]
     * </code>
     * For non-partitioned table partition by value would be NONE. For any other type of partition timestamp
     * has to be defined as reference to TIMESTAMP (type) column.
     *
     * @param executionModel   created from parsed sql.
     * @param executionContext provides access to bind variables and athorization module
     * @throws SqlException contains text of error and error position in SQL text.
     */
    private CompiledQuery createTableWithRetries(
            ExecutionModel executionModel,
            SqlExecutionContext executionContext
    ) throws SqlException {
        return executeWithRetries(createTableMethod, executionModel, configuration.getCreateAsSelectRetryCount(), executionContext);
    }

    private CompiledQuery dropTable(SqlExecutionContext executionContext) throws SqlException {
        CharSequence tok;
        expectKeyword(lexer, "table");

        final int tableNamePosition = lexer.getPosition();

        tok = GenericLexer.unquote(expectToken(lexer, "table name"));

        tableExistsOrFail(tableNamePosition, tok, executionContext);

        CharSequence tableName = GenericLexer.immutableOf(tok);
        engine.remove(executionContext.getCairoSecurityContext(), path, tableName);

        return compiledQuery.ofDrop();
    }

    @NotNull
    private CompiledQuery executeCopy(SqlExecutionContext executionContext, CopyModel executionModel) throws SqlException {
        setupTextLoaderFromModel(executionModel);
        if (Chars.equalsLowerCaseAscii(executionModel.getFileName().token, "stdin")) {
            return compiledQuery.ofCopyRemote(textLoader);
        }
        copyTable(executionContext, executionModel);
        return compiledQuery.ofCopyLocal();
    }

    private CompiledQuery executeWithRetries(
            ExecutableMethod method,
            ExecutionModel executionModel,
            int retries,
            SqlExecutionContext executionContext
    ) throws SqlException {
        int attemptsLeft = retries;
        do {
            try {
                return method.execute(executionModel, executionContext);
            } catch (ReaderOutOfDateException e) {
                attemptsLeft--;
                clear();
                lexer.restart();
                executionModel = compileExecutionModel(executionContext);
            }
        } while (attemptsLeft > 0);

        throw SqlException.position(0).put("underlying cursor is extremely volatile");
    }

    RecordCursorFactory generate(QueryModel queryModel, SqlExecutionContext executionContext) throws SqlException {
        return codeGenerator.generate(queryModel, executionContext);
    }

    private CompiledQuery insert(ExecutionModel executionModel, SqlExecutionContext executionContext) throws SqlException {
        final InsertModel model = (InsertModel) executionModel;
        final ExpressionNode name = model.getTableName();
        tableExistsOrFail(name.position, name.token, executionContext);

        ObjList<Function> valueFunctions = null;
        try (TableReader reader = engine.getReader(executionContext.getCairoSecurityContext(), name.token, TableUtils.ANY_TABLE_VERSION)) {
            final long structureVersion = reader.getVersion();
            final RecordMetadata metadata = reader.getMetadata();
            final int writerTimestampIndex = metadata.getTimestampIndex();
            final CharSequenceHashSet columnSet = model.getColumnSet();
            final int columnSetSize = columnSet.size();
            final ColumnFilter columnFilter;
            Function timestampFunction = null;
            if (columnSetSize > 0) {
                listColumnFilter.clear();
                columnFilter = listColumnFilter;
                valueFunctions = new ObjList<>(columnSetSize);
                for (int i = 0; i < columnSetSize; i++) {
                    int index = metadata.getColumnIndex(columnSet.get(i));
                    if (index < 0) {
                        throw SqlException.invalidColumn(model.getColumnPosition(i), columnSet.get(i));
                    }

                    final Function function = functionParser.parseFunction(model.getColumnValues().getQuick(i), metadata, executionContext);
                    if (!isAssignableFrom(metadata.getColumnType(index), function.getType())) {
                        throw SqlException.$(model.getColumnValues().getQuick(i).position, "inconvertible types: ").put(ColumnType.nameOf(function.getType())).put(" -> ").put(ColumnType.nameOf(metadata.getColumnType(index)));
                    }

                    if (i == writerTimestampIndex) {
                        timestampFunction = function;
                    } else {
                        valueFunctions.add(function);
                        listColumnFilter.add(index);
                    }
                }
            } else {
                final int columnCount = metadata.getColumnCount();
                entityColumnFilter.of(columnCount);
                columnFilter = entityColumnFilter;
                valueFunctions = new ObjList<>(columnCount);
                for (int i = 0; i < columnCount; i++) {
                    Function function = functionParser.parseFunction(model.getColumnValues().getQuick(i), EmptyRecordMetadata.INSTANCE, executionContext);
                    if (!isAssignableFrom(metadata.getColumnType(i), function.getType())) {
                        throw SqlException.$(model.getColumnValues().getQuick(i).position, "inconvertible types: ").put(ColumnType.nameOf(function.getType())).put(" -> ").put(ColumnType.nameOf(metadata.getColumnType(i)));
                    }
                    if (i == writerTimestampIndex) {
                        timestampFunction = function;
                    } else {
                        valueFunctions.add(function);
                    }
                }
            }

            // validate timestamp
            if (writerTimestampIndex > -1 && timestampFunction == null) {
                throw SqlException.$(0, "insert statement must populate timestamp");
            }

            VirtualRecord record = new VirtualRecord(valueFunctions);
            RecordToRowCopier copier = assembleRecordToRowCopier(asm, record, metadata, columnFilter);
            return compiledQuery.ofInsert(new InsertStatementImpl(engine, Chars.toString(name.token), record, copier, timestampFunction, structureVersion));
        } catch (SqlException e) {
            Misc.freeObjList(valueFunctions);
            throw e;
        }
    }

    private CompiledQuery insertAsSelect(ExecutionModel executionModel, SqlExecutionContext executionContext) throws SqlException {
        final InsertModel model = (InsertModel) executionModel;
        final ExpressionNode name = model.getTableName();
        tableExistsOrFail(name.position, name.token, executionContext);

        try (TableWriter writer = engine.getWriter(executionContext.getCairoSecurityContext(), name.token);
             RecordCursorFactory factory = generate(model.getQueryModel(), executionContext)) {

            final RecordMetadata cursorMetadata = factory.getMetadata();
            final RecordMetadata writerMetadata = writer.getMetadata();
            final int writerTimestampIndex = writerMetadata.getTimestampIndex();
            final int cursorTimestampIndex = cursorMetadata.getTimestampIndex();

            // fail when target table requires chronological data and cursor cannot provide it
            if (writerTimestampIndex > -1 && cursorTimestampIndex == -1) {
                throw SqlException.$(name.position, "select clause must provide timestamp column");
            }

            final RecordToRowCopier copier;

            boolean noTimestampColumn = true;

            CharSequenceHashSet columnSet = model.getColumnSet();
            final int columnSetSize = columnSet.size();
            if (columnSetSize > 0) {
                // validate type cast

                // clear list column filter to re-populate it again
                listColumnFilter.clear();

                for (int i = 0; i < columnSetSize; i++) {
                    CharSequence columnName = columnSet.get(i);
                    int index = writerMetadata.getColumnIndexQuiet(columnName);
                    if (index == -1) {
                        throw SqlException.invalidColumn(model.getColumnPosition(i), columnName);
                    }

                    if (index == writerTimestampIndex) {
                        noTimestampColumn = false;
                    }

                    int fromType = cursorMetadata.getColumnType(i);
                    int toType = writerMetadata.getColumnType(index);
                    if (isAssignableFrom(toType, fromType)) {
                        listColumnFilter.add(index);
                    } else {
                        throw SqlException.$(model.getColumnPosition(i), "inconvertible types: ").put(ColumnType.nameOf(fromType)).put(" -> ").put(ColumnType.nameOf(toType));
                    }
                }

                // fail when target table requires chronological data and timestamp column
                // is not in the list of columns to be updated
                if (writerTimestampIndex > -1 && noTimestampColumn) {
                    throw SqlException.$(model.getColumnPosition(0), "column list must include timestamp");
                }

                copier = assembleRecordToRowCopier(asm, cursorMetadata, writerMetadata, listColumnFilter);
            } else {

                final int n = writerMetadata.getColumnCount();
                if (n > cursorMetadata.getColumnCount()) {
                    throw SqlException.$(model.getSelectKeywordPosition(), "not enough columns selected");
                }

                for (int i = 0; i < n; i++) {
                    int fromType = cursorMetadata.getColumnType(i);
                    int toType = writerMetadata.getColumnType(i);
                    if (isAssignableFrom(toType, fromType)) {
                        continue;
                    }

                    // We are going on a limp here. There is nowhere to position this error in our model.
                    // We will try to position on column (i) inside cursor's query model. Assumption is that
                    // it will always have a column, e.g. has been processed by optimiser
                    assert i < model.getQueryModel().getColumns().size();
                    throw SqlException.$(model.getQueryModel().getColumns().getQuick(i).getAst().position, "inconvertible types: ").put(ColumnType.nameOf(fromType)).put(" -> ").put(ColumnType.nameOf(toType));
                }

                entityColumnFilter.of(writerMetadata.getColumnCount());

                copier = assembleRecordToRowCopier(asm, cursorMetadata, writerMetadata, entityColumnFilter);
            }

            try (RecordCursor cursor = factory.getCursor(executionContext)) {
                try {
                    if (writerTimestampIndex == -1) {
                        copyUnordered(cursor, writer, copier);
                    } else {
                        copyOrdered(writer, cursor, copier, cursorTimestampIndex);
                    }
                } catch (CairoException e) {
                    // rollback data when system error occurs
                    writer.rollback();
                    throw e;
                }
            }
        }
        return compiledQuery.ofInsertAsSelect();
    }

    private ExecutionModel lightlyValidateInsertModel(InsertModel model) throws SqlException {
        ExpressionNode tableName = model.getTableName();
        if (tableName.type != ExpressionNode.LITERAL) {
            throw SqlException.$(tableName.position, "literal expected");
        }

        if (model.getColumnSet().size() > 0 && model.getColumnSet().size() != model.getColumnValues().size()) {
            throw SqlException.$(model.getColumnPosition(0), "value count does not match column count");
        }

        return model;
    }

    private boolean removeTableDirectory(CreateTableModel model) {
        if (engine.removeDirectory(path, model.getName().token)) {
            return true;
        }
        LOG.error()
                .$("failed to clean up after create table failure [path=").$(path)
                .$(", errno=").$(configuration.getFilesFacade().errno())
                .$(']').$();
        return false;
    }

    private CompiledQuery repairTables(SqlExecutionContext executionContext) throws SqlException {
        CharSequence tok;
        tok = SqlUtil.fetchNext(lexer);
        if (tok == null || !Chars.equalsLowerCaseAscii(tok, "table")) {
            throw SqlException.$(lexer.lastTokenPosition(), "'table' expected");
        }

        do {
            tok = SqlUtil.fetchNext(lexer);

            if (tok == null || Chars.equals(tok, ',')) {
                throw SqlException.$(lexer.getPosition(), "table name expected");
            }

            if (Chars.isQuoted(tok)) {
                tok = GenericLexer.unquote(tok);
            }
            tableExistsOrFail(lexer.lastTokenPosition(), tok, executionContext);

            try {
                engine.getWriter(executionContext.getCairoSecurityContext(), tok).close();
            } catch (CairoException e) {
                LOG.info().$("table busy [table=").$(tok).$(", e=").$((Sinkable) e).$(']').$();
                throw SqlException.$(lexer.lastTokenPosition(), "table '").put(tok).put("' is busy");
            }
            tok = SqlUtil.fetchNext(lexer);

        } while (tok != null && Chars.equals(tok, ','));
        return compiledQuery.ofRepair();
    }

    void setFullSatJoins(boolean value) {
        codeGenerator.setFullFatJoins(value);
    }

    private void setupTextLoaderFromModel(CopyModel model) {
        textLoader.clear();
        textLoader.setState(TextLoader.ANALYZE_STRUCTURE);
        // todo: configure the following
        //   - when happens when data row errors out, max errors may be?
        //   - we should be able to skip X rows from top, dodgy headers etc.
        textLoader.configureDestination(model.getTableName().token, false, false, Atomicity.SKIP_ROW);
    }

    private void tableExistsOrFail(int position, CharSequence tableName, SqlExecutionContext executionContext) throws SqlException {
        if (engine.getStatus(executionContext.getCairoSecurityContext(), path, tableName) == TableUtils.TABLE_DOES_NOT_EXIST) {
            throw SqlException.$(position, "table '").put(tableName).put("' does not exist");
        }
    }

    ExecutionModel testCompileModel(CharSequence query, SqlExecutionContext executionContext) throws SqlException {
        clear();
        lexer.of(query);
        return compileExecutionModel(executionContext);
    }

    // this exposed for testing only
    ExpressionNode testParseExpression(CharSequence expression, QueryModel model) throws SqlException {
        clear();
        lexer.of(expression);
        return parser.expr(lexer, model);
    }

    // test only
    void testParseExpression(CharSequence expression, ExpressionParserListener listener) throws SqlException {
        clear();
        lexer.of(expression);
        parser.expr(lexer, listener);
    }

    private CompiledQuery truncateTables(SqlExecutionContext executionContext) throws SqlException {
        CharSequence tok;
        tok = SqlUtil.fetchNext(lexer);

        if (tok == null) {
            throw SqlException.$(lexer.getPosition(), "'table' expected");
        }

        if (!Chars.equalsLowerCaseAscii(tok, "table")) {
            throw SqlException.$(lexer.lastTokenPosition(), "'table' expected");
        }

        tableWriters.clear();
        try {
            try {
                do {
                    tok = SqlUtil.fetchNext(lexer);

                    if (tok == null || Chars.equals(tok, ',')) {
                        throw SqlException.$(lexer.getPosition(), "table name expected");
                    }

                    if (Chars.isQuoted(tok)) {
                        tok = GenericLexer.unquote(tok);
                    }
                    tableExistsOrFail(lexer.lastTokenPosition(), tok, executionContext);

                    try {
                        tableWriters.add(engine.getWriter(executionContext.getCairoSecurityContext(), tok));
                    } catch (CairoException e) {
                        LOG.info().$("table busy [table=").$(tok).$(", e=").$((Sinkable) e).$(']').$();
                        throw SqlException.$(lexer.lastTokenPosition(), "table '").put(tok).put("' is busy");
                    }
                    tok = SqlUtil.fetchNext(lexer);

                } while (tok != null && Chars.equals(tok, ','));
            } catch (SqlException e) {
                for (int i = 0, n = tableWriters.size(); i < n; i++) {
                    tableWriters.getQuick(i).close();
                }
                throw e;
            }

            for (int i = 0, n = tableWriters.size(); i < n; i++) {
                try (TableWriter writer = tableWriters.getQuick(i)) {
                    try {
                        if (engine.lockReaders(writer.getName())) {
                            try {
                                writer.truncate();
                            } finally {
                                engine.unlockReaders(writer.getName());
                            }
                        } else {
                            throw SqlException.$(0, "there is an active query against '").put(writer.getName()).put("'. Try again.");
                        }
                    } catch (CairoException | CairoError e) {
                        LOG.error().$("could truncate [table=").$(writer.getName()).$(", e=").$((Sinkable) e).$(']').$();
                        throw e;
                    }
                }
            }
        } finally {
            tableWriters.clear();
        }
        return compiledQuery.ofTruncate();
    }

    private InsertModel validateAndOptimiseInsertAsSelect(
            InsertModel model,
            SqlExecutionContext executionContext
    ) throws SqlException {
        final QueryModel queryModel = optimiser.optimise(model.getQueryModel(), executionContext);
        int targetColumnCount = model.getColumnSet().size();
        if (targetColumnCount > 0 && queryModel.getColumns().size() != targetColumnCount) {
            throw SqlException.$(model.getTableName().position, "column count mismatch");
        }
        model.setQueryModel(queryModel);
        return model;
    }

    private void validateTableModelAndCreateTypeCast(
            CreateTableModel model,
            RecordMetadata metadata,
            @Transient IntIntHashMap typeCast) throws SqlException {
        CharSequenceObjHashMap<ColumnCastModel> castModels = model.getColumnCastModels();
        ObjList<CharSequence> castColumnNames = castModels.keys();

        for (int i = 0, n = castColumnNames.size(); i < n; i++) {
            CharSequence columnName = castColumnNames.getQuick(i);
            int index = metadata.getColumnIndexQuiet(columnName);
            // the only reason why columns cannot be found at this stage is
            // concurrent table modification of table structure
            if (index == -1) {
                // Cast isn't going to go away when we re-parse SQL. We must make this
                // permanent error
                throw SqlException.invalidColumn(castModels.get(columnName).getColumnNamePos(), columnName);
            }
            ColumnCastModel ccm = castModels.get(columnName);
            int from = metadata.getColumnType(index);
            int to = ccm.getColumnType();
            if (isCompatibleCase(from, to)) {
                typeCast.put(index, to);
            } else {
                throw SqlException.$(ccm.getColumnTypePos(),
                        "unsupported cast [from=").put(ColumnType.nameOf(from)).put(",to=").put(ColumnType.nameOf(to)).put(']');
            }
        }

        // validate type of timestamp column
        // no need to worry that column will not resolve
        ExpressionNode timestamp = model.getTimestamp();
        if (timestamp != null && metadata.getColumnType(timestamp.token) != ColumnType.TIMESTAMP) {
            throw SqlException.position(timestamp.position).put("TIMESTAMP column expected [actual=").put(ColumnType.nameOf(metadata.getColumnType(timestamp.token))).put(']');
        }
    }

    @FunctionalInterface
    private interface KeywordBasedExecutor {
        CompiledQuery execute(SqlExecutionContext executionContext) throws SqlException;
    }

    @FunctionalInterface
    private interface ExecutableMethod {
        CompiledQuery execute(ExecutionModel model, SqlExecutionContext sqlExecutionContext) throws SqlException;
    }

    public interface RecordToRowCopier {
        void copy(Record record, TableWriter.Row row);
    }

    private static class TableStructureAdapter implements TableStructure {
        private CreateTableModel model;
        private RecordMetadata metadata;
        private IntIntHashMap typeCast;

        @Override
        public int getColumnCount() {
            return model.getColumnCount();
        }

        @Override
        public CharSequence getColumnName(int columnIndex) {
            return model.getColumnName(columnIndex);
        }

        @Override
        public int getColumnType(int columnIndex) {
            int castIndex = typeCast.keyIndex(columnIndex);
            if (castIndex < 0) {
                return typeCast.valueAt(castIndex);
            }
            return metadata.getColumnType(columnIndex);
        }

        @Override
        public int getIndexBlockCapacity(int columnIndex) {
            return model.getIndexBlockCapacity(columnIndex);
        }

        @Override
        public boolean getIndexedFlag(int columnIndex) {
            return model.getIndexedFlag(columnIndex);
        }

        @Override
        public int getPartitionBy() {
            return model.getPartitionBy();
        }

        @Override
        public boolean getSymbolCacheFlag(int columnIndex) {
            final ColumnCastModel ccm = model.getColumnCastModels().get(metadata.getColumnName(columnIndex));
            if (ccm != null) {
                return ccm.getSymbolCacheFlag();
            }
            return model.getSymbolCacheFlag(columnIndex);
        }

        @Override
        public int getSymbolCapacity(int columnIndex) {
            final ColumnCastModel ccm = model.getColumnCastModels().get(metadata.getColumnName(columnIndex));
            if (ccm != null) {
                return ccm.getSymbolCapacity();
            } else {
                return model.getSymbolCapacity(columnIndex);
            }
        }

        @Override
        public CharSequence getTableName() {
            return model.getTableName();
        }

        @Override
        public int getTimestampIndex() {
            return model.getTimestampIndex();
        }

        TableStructureAdapter of(CreateTableModel model, RecordMetadata metadata, IntIntHashMap typeCast) {
            this.model = model;
            this.metadata = metadata;
            this.typeCast = typeCast;
            return this;
        }
    }
}
