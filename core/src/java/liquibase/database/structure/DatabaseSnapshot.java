package liquibase.database.structure;

import liquibase.database.CacheDatabase;
import liquibase.database.Database;
import liquibase.database.H2Database;
import liquibase.database.template.JdbcTemplate;
import liquibase.diff.DiffStatusListener;
import liquibase.exception.JDBCException;
import liquibase.migrator.Migrator;
import liquibase.util.StringUtils;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

public class DatabaseSnapshot {

    private DatabaseMetaData databaseMetaData;
    private Database database;

    private Set<Table> tables = new HashSet<Table>();
    private Set<View> views = new HashSet<View>();
    private Set<Column> columns = new HashSet<Column>();
    private Set<ForeignKey> foreignKeys = new HashSet<ForeignKey>();
    private Set<Index> indexes = new HashSet<Index>();
    private Set<PrimaryKey> primaryKeys = new HashSet<PrimaryKey>();
    private Set<Sequence> sequences = new HashSet<Sequence>();


    private Map<String, Table> tablesMap = new HashMap<String, Table>();
    private Map<String, View> viewsMap = new HashMap<String, View>();
    private Map<String, Column> columnsMap = new HashMap<String, Column>();
    private Set<DiffStatusListener> statusListeners;

    private Logger log = Logger.getLogger(Migrator.DEFAULT_LOG_NAME);


    /**
     * Creates an empty database snapshot
     */
    public DatabaseSnapshot() {
    }

    /**
     * Creates a snapshot of the given database with no status listeners
     */
    public DatabaseSnapshot(Database database) throws JDBCException {
        this(database, null);
    }

    /**
     * Creates a snapshot of the given database.
     */
    public DatabaseSnapshot(Database database, Set<DiffStatusListener> statusListeners) throws JDBCException {
        try {
            this.database = database;
            this.databaseMetaData = database.getConnection().getMetaData();
            this.statusListeners = statusListeners;

            readTablesAndViews();
            readColumns();
            readForeignKeyInformation();
            readPrimaryKeys();
            readIndexes();
            readSequences();

            this.tables = new HashSet<Table>(tablesMap.values());
            this.views = new HashSet<View>(viewsMap.values());
            this.columns = new HashSet<Column>(columnsMap.values());
        } catch (SQLException e) {
            throw new JDBCException(e);
        }
    }


    public Database getDatabase() {
        return database;
    }

    public Set<Table> getTables() {
        return tables;
    }

    public Set<View> getViews() {
        return views;
    }

    public Column getColumn(Column column) {
        if (column.getTable() == null) {
            return columnsMap.get(column.getView().getName() + "." + column.getName());
        } else {
            return columnsMap.get(column.getTable().getName() + "." + column.getName());
        }
    }

    public Column getColumn(String tableName, String columnName) {
        return columnsMap.get(tableName + "." + columnName);
    }

    public Set<Column> getColumns() {
        return columns;
    }

    public Set<ForeignKey> getForeignKeys() {
        return foreignKeys;
    }

    public Set<Index> getIndexes() {
        return indexes;
    }

    public Set<PrimaryKey> getPrimaryKeys() {
        return primaryKeys;
    }


    public Set<Sequence> getSequences() {
        return sequences;
    }

    private void readTablesAndViews() throws SQLException, JDBCException {
        updateListeners("Reading tables for " + database.toString() + " ...");
        ResultSet rs = databaseMetaData.getTables(database.getCatalogName(), database.getSchemaName(), null, new String[]{"TABLE", "VIEW"});
        while (rs.next()) {
            String type = rs.getString("TABLE_TYPE");
            String name = rs.getString("TABLE_NAME");
            String schemaName = rs.getString("TABLE_SCHEM");
            String catalogName = rs.getString("TABLE_CAT");

            if (database.isSystemTable(catalogName, schemaName, name) || database.isLiquibaseTable(name)) {
                continue;
            }

            if ("TABLE".equals(type)) {
                Table table = new Table();
                table.setName(name);
                tablesMap.put(name, table);
            } else if ("VIEW".equals(type)) {
                View view = new View();
                view.setName(name);
                view.setDefinition(database.getViewDefinition(name));

                viewsMap.put(name, view);

            }
        }
        rs.close();
    }

    private void readColumns() throws SQLException, JDBCException {
        updateListeners("Reading columns for " + database.toString() + " ...");

        ResultSet rs = databaseMetaData.getColumns(database.getCatalogName(), database.getSchemaName(), null, null);
        while (rs.next()) {
            Column columnInfo = new Column();

            String tableName = rs.getString("TABLE_NAME");
            String columnName = rs.getString("COLUMN_NAME");
            String schemaName = rs.getString("TABLE_SCHEM");
            String catalogName = rs.getString("TABLE_CAT");

            if (database.isSystemTable(catalogName, schemaName, tableName) || database.isLiquibaseTable(tableName)) {
                continue;
            }

            Table table = tablesMap.get(tableName);
            if (table == null) {
                View view = viewsMap.get(tableName);
                if (view == null) {
                    log.info("Could not find table or view " + tableName + " for column " + columnName);
                    continue;
                } else {
                    columnInfo.setView(view);
                    view.getColumns().add(columnInfo);
                }
            } else {
                columnInfo.setTable(table);
                table.getColumns().add(columnInfo);
            }

            columnInfo.setName(columnName);
            columnInfo.setDataType(rs.getInt("DATA_TYPE"));
            columnInfo.setColumnSize(rs.getInt("COLUMN_SIZE"));
            columnInfo.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
            columnInfo.setTypeName(rs.getString("TYPE_NAME"));
            String defaultValue = rs.getString("COLUMN_DEF");
            columnInfo.setAutoIncrement(isAutoIncrement(defaultValue, database));
            columnInfo.setDefaultValue(translateDefaultValue(defaultValue, database));

            int nullable = rs.getInt("NULLABLE");
            if (nullable == DatabaseMetaData.columnNoNulls) {
                columnInfo.setNullable(false);
            } else if (nullable == DatabaseMetaData.columnNullable) {
                columnInfo.setNullable(true);
            }

            columnsMap.put(tableName + "." + columnName, columnInfo);
        }
        rs.close();
    }

    private boolean isAutoIncrement(String defaultValue, Database database) {
        if (database instanceof H2Database) {
            if (StringUtils.trimToEmpty(defaultValue).startsWith("(NEXT VALUE FOR PUBLIC.SYSTEM_SEQUENCE_")) {
                return true;
            }
        }
        return false;
    }

    private String translateDefaultValue(String defaultValue, Database database) {
        if (database instanceof H2Database) {
            if (StringUtils.trimToEmpty(defaultValue).startsWith("(NEXT VALUE FOR PUBLIC.SYSTEM_SEQUENCE_")) {
                return null;
            }
            return defaultValue;
        } else if (database instanceof CacheDatabase) {
            if (defaultValue != null) {
                if (defaultValue.charAt(0) == '"' && defaultValue.charAt(defaultValue.length() - 1) == '"') {
                    defaultValue = defaultValue.substring(1, defaultValue.length() - 2);
                    return "'" + defaultValue + "'";
                } else if (defaultValue.startsWith("$")) {
                    return "OBJECTSCRIPT '" + defaultValue + "'";
                }
            }
        }
        return defaultValue;
    }

    private void readForeignKeyInformation() throws JDBCException, SQLException {
        updateListeners("Reading foreign keys for " + database.toString() + " ...");

        for (Table table : tablesMap.values()) {
            ResultSet rs = databaseMetaData.getExportedKeys(database.getCatalogName(), database.getSchemaName(), table.getName());
            while (rs.next()) {
                ForeignKey fkInfo = new ForeignKey();

                String pkTableName = rs.getString("PKTABLE_NAME");
                String pkColumn = rs.getString("PKCOLUMN_NAME");
                Table pkTable = tablesMap.get(pkTableName);
                if (pkTable == null) {
                    throw new JDBCException("Could not find table " + pkTableName + " for column " + pkColumn);
                }
                fkInfo.setPrimaryKeyTable(pkTable);
                fkInfo.setPrimaryKeyColumn(pkColumn);

                String fkTableName = rs.getString("FKTABLE_NAME");
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                Table fkTable = tablesMap.get(fkTableName);
                if (fkTable == null) {
                    throw new JDBCException("Could not find table " + fkTableName + " for column " + fkColumn);
                }
                fkInfo.setForeignKeyTable(fkTable);
                fkInfo.setForeignKeyColumn(fkColumn);

                fkInfo.setName(rs.getString("FK_NAME"));

                if (database.supportsInitiallyDeferrableColumns()) {
                    short deferrablility = rs.getShort("DEFERRABILITY");
                    if (deferrablility == DatabaseMetaData.importedKeyInitiallyDeferred) {
                        fkInfo.setDeferrable(Boolean.TRUE);
                        fkInfo.setInitiallyDeferred(Boolean.TRUE);
                    } else if (deferrablility == DatabaseMetaData.importedKeyInitiallyImmediate) {
                        fkInfo.setDeferrable(Boolean.TRUE);
                        fkInfo.setInitiallyDeferred(Boolean.FALSE);
                    } else if (deferrablility == DatabaseMetaData.importedKeyNotDeferrable) {
                        fkInfo.setDeferrable(Boolean.FALSE);
                        fkInfo.setInitiallyDeferred(Boolean.FALSE);
                    }
                }


                foreignKeys.add(fkInfo);
            }

            rs.close();
        }
    }

    private void readIndexes() throws JDBCException, SQLException {
        updateListeners("Reading indexes for " + database.toString() + " ...");

        for (Table table : tablesMap.values()) {
            ResultSet rs;
            try {
                rs = databaseMetaData.getIndexInfo(database.getCatalogName(), database.getSchemaName(), table.getName(), false, true);
            } catch (SQLException e) {
                throw e;
            }
            Map<String, Index> indexMap = new HashMap<String, Index>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                short type = rs.getShort("TYPE");
                String tableName = rs.getString("TABLE_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                short position = rs.getShort("ORDINAL_POSITION");

                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }

                if (columnName == null) {
                    //nothing to index, not sure why these come through sometimes
                    continue;
                }
                Index indexInformation;
                if (indexMap.containsKey(indexName)) {
                    indexInformation = indexMap.get(indexName);
                } else {
                    indexInformation = new Index();
                    indexInformation.setTableName(tableName);
                    indexInformation.setName(indexName);
                    indexMap.put(indexName, indexInformation);
                }
                indexInformation.getColumns().add(position - 1, columnName);
            }
            for (String key : indexMap.keySet()) {
                indexes.add(indexMap.get(key));
            }
            rs.close();
        }

        Set<Index> indexesToRemove = new HashSet<Index>();
        //remove PK indexes
        for (Index index : indexes) {
            for (PrimaryKey pk : primaryKeys) {
                if (index.getTableName().equalsIgnoreCase(pk.getTableName())
                        && index.getColumnNames().equals(pk.getColumnNames())) {
                    indexesToRemove.add(index);
                }
            }
        }
        indexes.removeAll(indexesToRemove);
    }

    private void readPrimaryKeys() throws JDBCException, SQLException {
        updateListeners("Reading primary keys for " + database.toString() + " ...");

        //we can't add directly to the this.primaryKeys hashSet because adding columns to an exising PK changes the hashCode and .contains() fails
        List<PrimaryKey> foundPKs = new ArrayList<PrimaryKey>();

        for (Table table : tablesMap.values()) {
            ResultSet rs = databaseMetaData.getPrimaryKeys(database.getCatalogName(), database.getSchemaName(), table.getName());

            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                short position = rs.getShort("KEY_SEQ");

                boolean foundExistingPK = false;
                for (PrimaryKey pk : foundPKs) {
                    if (pk.getTableName().equals(tableName)) {
                        pk.addColumnName(position - 1, columnName);

                        foundExistingPK = true;
                    }
                }

                if (!foundExistingPK) {
                    PrimaryKey primaryKey = new PrimaryKey();
                    primaryKey.setTableName(tableName);
                    primaryKey.addColumnName(position - 1, columnName);
                    primaryKey.setName(rs.getString("PK_NAME"));

                    foundPKs.add(primaryKey);
                }
            }

            rs.close();
        }

        this.primaryKeys.addAll(foundPKs);
    }

    private void readSequences() throws JDBCException, SQLException {
        updateListeners("Reading sequences for " + database.toString() + " ...");

        if (database.supportsSequences()) {
            //noinspection unchecked
            List<String> sequenceNamess = (List<String>) new JdbcTemplate(database).queryForList(database.createFindSequencesSQL(), String.class);

            for (String sequenceName : sequenceNamess) {
                Sequence seq = new Sequence();
                seq.setName(sequenceName);

                sequences.add(seq);
            }
        }
    }

    private void updateListeners(String message) {
        if (this.statusListeners == null) {
            return;
        }
        for (DiffStatusListener listener : this.statusListeners) {
            listener.statusUpdate(message);
        }
    }
}
