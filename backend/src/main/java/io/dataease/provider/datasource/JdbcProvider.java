package io.dataease.provider.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.google.gson.Gson;
import io.dataease.dto.datasource.*;
import io.dataease.exception.DataEaseException;
import io.dataease.i18n.Translator;
import io.dataease.plugins.common.constants.DatasourceTypes;
import io.dataease.plugins.common.dto.datasource.TableField;
import io.dataease.plugins.common.request.datasource.DatasourceRequest;
import io.dataease.plugins.datasource.entity.JdbcConfiguration;
import io.dataease.plugins.datasource.provider.DefaultJdbcProvider;
import io.dataease.plugins.datasource.query.QueryProvider;
import io.dataease.provider.ProviderFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.beans.PropertyVetoException;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

@Service("jdbc")
public class JdbcProvider extends DefaultJdbcProvider {


    @Override
    public boolean isUseDatasourcePool(){
        return true;
    }
    @Override
    public String getType(){
    return "built-in";
    }
    /**
     * 增加缓存机制 key 由 'provider_sql_' dsr.datasource.id dsr.table dsr.query共4部分组成，命中则使用缓存直接返回不再执行sql逻辑
     * @param dsr
     * @return
     * @throws Exception
     */

    /**
     * 这里使用声明式缓存不是很妥当
     * 改为chartViewService中使用编程式缓存
     *
     * @Cacheable( value = JdbcConstants.JDBC_PROVIDER_KEY,
     * key = "'provider_sql_' + #dsr.datasource.id + '_' + #dsr.table + '_' + #dsr.query",
     * condition = "#dsr.pageSize == null || #dsr.pageSize == 0L"
     * )
     */

    public void exec(DatasourceRequest datasourceRequest) throws Exception {
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = connection.createStatement()) {
            Boolean result = stat.execute(datasourceRequest.getQuery());
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            DataEaseException.throwException(e);
        }
    }


    @Override
    public List<TableField> getTableFileds(DatasourceRequest datasourceRequest) throws Exception {
        if(datasourceRequest.getDatasource().getType().equalsIgnoreCase("mongo")){
            datasourceRequest.setQuery("select * from " + datasourceRequest.getTable());
            return fetchResultField(datasourceRequest);
        }
        List<TableField> list = new LinkedList<>();
        try (Connection connection = getConnectionFromPool(datasourceRequest)) {
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase("oracle")) {
                Method setRemarksReporting = extendedJdbcClassLoader.loadClass("oracle.jdbc.driver.OracleConnection").getMethod("setRemarksReporting",boolean.class);
                setRemarksReporting.invoke(((DruidPooledConnection) connection).getConnection(), true);
            }
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            ResultSet resultSet = databaseMetaData.getColumns(null, "%", datasourceRequest.getTable(), "%");
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                String database;
                if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name()) || datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.impala.name())) {
                    database = resultSet.getString("TABLE_SCHEM");
                } else {
                    database = resultSet.getString("TABLE_CAT");
                }
                if (database != null) {
                    if (tableName.equals(datasourceRequest.getTable()) && database.equalsIgnoreCase(getDatabase(datasourceRequest))) {
                        TableField tableField = getTableFiled(resultSet, datasourceRequest);
                        list.add(tableField);
                    }
                } else {
                    if (tableName.equals(datasourceRequest.getTable())) {
                        TableField tableField = getTableFiled(resultSet, datasourceRequest);
                        list.add(tableField);
                    }
                }
            }
            resultSet.close();
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            if(datasourceRequest.getDatasource().getType().equalsIgnoreCase("ds_doris")){
                datasourceRequest.setQuery("select * from " + datasourceRequest.getTable());
                return fetchResultField(datasourceRequest);
            }else {
                DataEaseException.throwException(Translator.get("i18n_datasource_connect_error") + e.getMessage());
            }

        }
        return list;
    }

    private TableField getTableFiled(ResultSet resultSet, DatasourceRequest datasourceRequest) throws SQLException {
        TableField tableField = new TableField();
        String colName = resultSet.getString("COLUMN_NAME");
        tableField.setFieldName(colName);
        String remarks = resultSet.getString("REMARKS");
        if (remarks == null || remarks.equals("")) {
            remarks = colName;
        }
        tableField.setRemarks(remarks);
        String dbType = resultSet.getString("TYPE_NAME").toUpperCase();
        tableField.setFieldType(dbType);
        if (dbType.equalsIgnoreCase("LONG")) {
            tableField.setFieldSize(65533);
        }
        if (StringUtils.isNotEmpty(dbType) && dbType.toLowerCase().contains("date") && tableField.getFieldSize() < 50) {
            tableField.setFieldSize(50);
        }

        if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name())) {
            QueryProvider qp = ProviderFactory.getQueryProvider(datasourceRequest.getDatasource().getType());
            tableField.setFieldSize(qp.transFieldSize(dbType));
        } else {
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.hive.name()) && tableField.getFieldType().equalsIgnoreCase("BOOLEAN")) {
                tableField.setFieldSize(1);
            } else {
                String size = resultSet.getString("COLUMN_SIZE");
                if (size == null) {
                    tableField.setFieldSize(1);
                } else {
                    tableField.setFieldSize(Integer.valueOf(size));
                }
            }
        }
        return tableField;
    }

    private String getDatabase(DatasourceRequest datasourceRequest) {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case engine_doris:
            case ds_doris:
            case mariadb:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                return mysqlConfiguration.getDataBase();
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                return sqlServerConfiguration.getDataBase();
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                return pgConfiguration.getDataBase();
            default:
                JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
                return jdbcConfiguration.getDataBase();
        }
    }

    @Override
    public List<TableField> fetchResultField(DatasourceRequest datasourceRequest) throws Exception {
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(datasourceRequest.getQuery())) {
            return fetchResultField(rs, datasourceRequest);
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            e.printStackTrace();
            DataEaseException.throwException(Translator.get("i18n_datasource_connect_error") + e.getMessage());
        }
        return new ArrayList<>();
    }


    @Override
    public Map<String, List> fetchResultAndField(DatasourceRequest datasourceRequest) throws Exception {
        Map<String, List> result = new HashMap<>();
        List<String[]> dataList;
        List<TableField> fieldList;
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(datasourceRequest.getQuery())) {
            fieldList = fetchResultField(rs, datasourceRequest);
            result.put("fieldList", fieldList);
            dataList = getDataResult(rs, datasourceRequest);
            result.put("dataList", dataList);
            return result;
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            DataEaseException.throwException(e);
        }
        return new HashMap<>();
    }

    private List<String[]> getDataResult(ResultSet rs, DatasourceRequest datasourceRequest) throws Exception {
        String charset = null;
        if(datasourceRequest != null && datasourceRequest.getDatasource().getType().equalsIgnoreCase("oracle")){
            JdbcConfiguration JdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
            if(StringUtils.isNotEmpty(JdbcConfiguration.getCharset()) && !JdbcConfiguration.getCharset().equalsIgnoreCase("Default") ){
                charset = JdbcConfiguration.getCharset();
            }
        }
        List<String[]> list = new LinkedList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (rs.next()) {
            String[] row = new String[columnCount];
            for (int j = 0; j < columnCount; j++) {
                int columType = metaData.getColumnType(j + 1);
                switch (columType) {
                    case Types.DATE:
                        if (rs.getDate(j + 1) != null) {
                            row[j] = rs.getDate(j + 1).toString();
                        }
                        break;
                    case Types.BOOLEAN:
                        row[j] = rs.getBoolean(j + 1) ? "1" : "0";
                        break;
                    default:
                        if(charset != null && StringUtils.isNotEmpty(rs.getString(j + 1))){
                            row[j] = new String(rs.getString(j + 1).getBytes(charset), "UTF-8");
                        }else {
                            row[j] = rs.getString(j + 1);
                        }
                        break;
                }
            }
            list.add(row);
        }
        return list;
    }

    private List<TableField> fetchResultField(ResultSet rs, DatasourceRequest datasourceRequest) throws Exception {
        List<TableField> fieldList = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        for (int j = 0; j < columnCount; j++) {
            String f = metaData.getColumnName(j + 1);
            String l = StringUtils.isNotEmpty(metaData.getColumnLabel(j + 1)) ? metaData.getColumnLabel(j + 1) : f;
            String t = metaData.getColumnTypeName(j + 1);
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.hive.name()) && l.contains(".")) {
                l = l.split("\\.")[1];
            }
            TableField field = new TableField();
            field.setFieldName(l);
            field.setRemarks(l);
            field.setFieldType(t);

            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name())) {
                QueryProvider qp = ProviderFactory.getQueryProvider(datasourceRequest.getDatasource().getType());
                field.setFieldSize(qp.transFieldSize(t));
            } else {
                field.setFieldSize(metaData.getColumnDisplaySize(j + 1));
            }
            if (t.equalsIgnoreCase("LONG")) {
                field.setFieldSize(65533);
            } //oracle LONG
            if (StringUtils.isNotEmpty(t) && t.toLowerCase().contains("date") && field.getFieldSize() < 50) {
                field.setFieldSize(50);
            }
            fieldList.add(field);
        }
        return fieldList;
    }
    @Override
    public List<String[]> getData(DatasourceRequest dsr) throws Exception {
        List<String[]> list = new LinkedList<>();
        try (Connection connection = getConnectionFromPool(dsr); Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(dsr.getQuery())) {
            list = getDataResult(rs, dsr);
            if (dsr.isPageable() && (dsr.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.sqlServer.name()) || dsr.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.db2.name()))) {
                Integer realSize = dsr.getPage() * dsr.getPageSize() < list.size() ? dsr.getPage() * dsr.getPageSize() : list.size();
                list = list.subList((dsr.getPage() - 1) * dsr.getPageSize(), realSize);
            }

        } catch (SQLException e) {
            DataEaseException.throwException("SQL ERROR" + e.getMessage());
        } catch (Exception e) {
            DataEaseException.throwException("Data source connection exception: " + e.getMessage());
        }
        return list;
    }

    @Override
    public Connection getConnection(DatasourceRequest datasourceRequest) throws Exception {
        String username = null;
        String password = null;
        String driver = null;
        String jdbcurl = null;
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        Properties props = new Properties();
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_doris:
            case engine_mysql:
            case ds_doris:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                username = mysqlConfiguration.getUsername();
                password = mysqlConfiguration.getPassword();
                driver = "com.mysql.jdbc.Driver";
                jdbcurl = mysqlConfiguration.getJdbc();
                break;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                username = sqlServerConfiguration.getUsername();
                password = sqlServerConfiguration.getPassword();
                driver = sqlServerConfiguration.getDriver();
                jdbcurl = sqlServerConfiguration.getJdbc();
                break;
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                username = oracleConfiguration.getUsername();
                password = oracleConfiguration.getPassword();
                driver = oracleConfiguration.getDriver();
                jdbcurl = oracleConfiguration.getJdbc();
                props.put("oracle.net.CONNECT_TIMEOUT", "5000");
                break;
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                username = pgConfiguration.getUsername();
                password = pgConfiguration.getPassword();
                driver = pgConfiguration.getDriver();
                jdbcurl = pgConfiguration.getJdbc();
                break;
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                username = chConfiguration.getUsername();
                password = chConfiguration.getPassword();
                driver = chConfiguration.getDriver();
                jdbcurl = chConfiguration.getJdbc();
                break;
            case mongo:
                MongodbConfiguration mongodbConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MongodbConfiguration.class);
                username = mongodbConfiguration.getUsername();
                password = mongodbConfiguration.getPassword();
                driver = mongodbConfiguration.getDriver();
                jdbcurl = mongodbConfiguration.getJdbc(datasourceRequest.getDatasource().getId());
                break;
            case redshift:
                RedshiftConfigration redshiftConfigration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfigration.class);
                username = redshiftConfigration.getUsername();
                password = redshiftConfigration.getPassword();
                driver = redshiftConfigration.getDriver();
                jdbcurl = redshiftConfigration.getJdbc();
                break;
            case hive:
                HiveConfiguration hiveConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), HiveConfiguration.class);
                username = hiveConfiguration.getUsername();
                password = hiveConfiguration.getPassword();
                driver = hiveConfiguration.getDriver();
                jdbcurl = hiveConfiguration.getJdbc();
                break;
            case impala:
                ImpalaConfiguration impalaConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), ImpalaConfiguration.class);
                username = impalaConfiguration.getUsername();
                password = impalaConfiguration.getPassword();
                driver = impalaConfiguration.getDriver();
                jdbcurl = impalaConfiguration.getJdbc();
                break;
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                username = db2Configuration.getUsername();
                password = db2Configuration.getPassword();
                driver = db2Configuration.getDriver();
                jdbcurl = db2Configuration.getJdbc();
                break;
            default:
                break;
        }

        Driver driverClass = (Driver) extendedJdbcClassLoader.loadClass(driver).newInstance();
        if (StringUtils.isNotBlank(username)) {
            props.setProperty("user", username);
            if (StringUtils.isNotBlank(password)) {
                props.setProperty("password", password);
            }
        }

        Connection conn = driverClass.connect(jdbcurl, props);
        return conn;
    }

    @Override
    public JdbcConfiguration setCredential(DatasourceRequest datasourceRequest, DruidDataSource dataSource) throws PropertyVetoException {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        JdbcConfiguration jdbcConfiguration = new JdbcConfiguration();
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_mysql:
            case engine_doris:
            case ds_doris:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                dataSource.setUrl(mysqlConfiguration.getJdbc());
                dataSource.setDriverClassName("com.mysql.jdbc.Driver");
                dataSource.setValidationQuery("select 1");
                jdbcConfiguration = mysqlConfiguration;
                break;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                dataSource.setDriverClassName(sqlServerConfiguration.getDriver());
                dataSource.setUrl(sqlServerConfiguration.getJdbc());
                dataSource.setValidationQuery("select 1");
                jdbcConfiguration = sqlServerConfiguration;
                break;
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                dataSource.setDriverClassName(oracleConfiguration.getDriver());
                dataSource.setUrl(oracleConfiguration.getJdbc());
                dataSource.setValidationQuery("select 1 from dual");
                jdbcConfiguration = oracleConfiguration;
                break;
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                dataSource.setDriverClassName(pgConfiguration.getDriver());
                dataSource.setUrl(pgConfiguration.getJdbc());
                jdbcConfiguration = pgConfiguration;
                break;
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                dataSource.setDriverClassName(chConfiguration.getDriver());
                dataSource.setUrl(chConfiguration.getJdbc());
                jdbcConfiguration = chConfiguration;
                break;
            case mongo:
                MongodbConfiguration mongodbConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MongodbConfiguration.class);
                dataSource.setDriverClassName(mongodbConfiguration.getDriver());
                dataSource.setUrl(mongodbConfiguration.getJdbc(datasourceRequest.getDatasource().getId()));
                jdbcConfiguration = mongodbConfiguration;
                break;
            case redshift:
                RedshiftConfigration redshiftConfigration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfigration.class);
                dataSource.setPassword(redshiftConfigration.getPassword());
                dataSource.setDriverClassName(redshiftConfigration.getDriver());
                dataSource.setUrl(redshiftConfigration.getJdbc());
                jdbcConfiguration = redshiftConfigration;
                break;
            case hive:
                HiveConfiguration hiveConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), HiveConfiguration.class);
                dataSource.setPassword(hiveConfiguration.getPassword());
                dataSource.setDriverClassName(hiveConfiguration.getDriver());
                dataSource.setUrl(hiveConfiguration.getJdbc());
                jdbcConfiguration = hiveConfiguration;
                break;
            case impala:
                ImpalaConfiguration impalaConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), ImpalaConfiguration.class);
                dataSource.setPassword(impalaConfiguration.getPassword());
                dataSource.setDriverClassName(impalaConfiguration.getDriver());
                dataSource.setUrl(impalaConfiguration.getJdbc());
                jdbcConfiguration = impalaConfiguration;
                break;
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                dataSource.setPassword(db2Configuration.getPassword());
                dataSource.setDriverClassName(db2Configuration.getDriver());
                dataSource.setUrl(db2Configuration.getJdbc());
                jdbcConfiguration = db2Configuration;
            default:
                break;
        }

        dataSource.setUsername(jdbcConfiguration.getUsername());
        dataSource.setDriverClassLoader(extendedJdbcClassLoader);
        dataSource.setPassword(jdbcConfiguration.getPassword());

        return jdbcConfiguration;
    }

    @Override
    public String getTablesSql(DatasourceRequest datasourceRequest) throws Exception {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case engine_mysql:
            case mariadb:
            case TiDB:
                JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
                return String.format("SELECT TABLE_NAME,TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s' ;", jdbcConfiguration.getDataBase());
            case engine_doris:
            case ds_doris:
            case StarRocks:
            case hive:
            case impala:
                return "show tables";
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                if (StringUtils.isEmpty(sqlServerConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT TABLE_NAME FROM \"DATABASE\".INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA = 'DS_SCHEMA' ;"
                        .replace("DATABASE", sqlServerConfiguration.getDataBase())
                        .replace("DS_SCHEMA", sqlServerConfiguration.getSchema());
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                if (StringUtils.isEmpty(oracleConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select table_name, owner, comments from all_tab_comments where owner='OWNER' AND table_type = 'TABLE' AND table_name in (select table_name from all_tables where owner='OWNER')".replaceAll("OWNER", oracleConfiguration.getSchema());
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                if (StringUtils.isEmpty(pgConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT tablename FROM  pg_tables WHERE  schemaname='SCHEMA' ;".replace("SCHEMA", pgConfiguration.getSchema());
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                return "SELECT name FROM system.tables where database='DATABASE';".replace("DATABASE", chConfiguration.getDataBase());
            case redshift:
                RedshiftConfigration redshiftConfigration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfigration.class);
                if (StringUtils.isEmpty(redshiftConfigration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT tablename FROM  pg_tables WHERE  schemaname='SCHEMA' ;".replace("SCHEMA", redshiftConfigration.getSchema());
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                if (StringUtils.isEmpty(db2Configuration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select TABNAME from syscat.tables  WHERE TABSCHEMA ='DE_SCHEMA' AND \"TYPE\" = 'T'".replace("DE_SCHEMA", db2Configuration.getSchema());
            default:
                return "show tables;";
        }
    }

    @Override
    public String getViewSql(DatasourceRequest datasourceRequest) throws Exception {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_doris:
            case engine_mysql:
            case ds_doris:
            case ck:
            case TiDB:
            case StarRocks:
                return null;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                if (StringUtils.isEmpty(sqlServerConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT TABLE_NAME FROM DATABASE.INFORMATION_SCHEMA.VIEWS WHERE  TABLE_SCHEMA = 'DS_SCHEMA' ;"
                        .replace("DATABASE", sqlServerConfiguration.getDataBase())
                        .replace("DS_SCHEMA", sqlServerConfiguration.getSchema());
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                if (StringUtils.isEmpty(oracleConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select table_name, owner, comments from all_tab_comments where owner='" + oracleConfiguration.getSchema() + "' AND table_type = 'VIEW'";
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                if (StringUtils.isEmpty(pgConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT viewname FROM  pg_views WHERE schemaname='SCHEMA' ;".replace("SCHEMA", pgConfiguration.getSchema());
            case redshift:
                RedshiftConfigration redshiftConfigration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfigration.class);
                if (StringUtils.isEmpty(redshiftConfigration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT viewname FROM  pg_views WHERE schemaname='SCHEMA' ;".replace("SCHEMA", redshiftConfigration.getSchema());

            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                if (StringUtils.isEmpty(db2Configuration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select TABNAME from syscat.tables  WHERE TABSCHEMA ='DE_SCHEMA' AND \"TYPE\" = 'V'".replace("DE_SCHEMA", db2Configuration.getSchema());

            default:
                return null;
        }
    }

    @Override
    public String getSchemaSql(DatasourceRequest datasourceRequest) {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
        switch (datasourceType) {
            case oracle:
                return "select * from all_users";
            case sqlServer:
                return "select name from sys.schemas;";
            case db2:
                return "select SCHEMANAME from syscat.SCHEMATA   WHERE \"DEFINER\" ='USER'".replace("USER", db2Configuration.getUsername().toUpperCase()) ;
            case pg:
                return "SELECT nspname FROM pg_namespace;";
            case redshift:
                return "SELECT nspname FROM pg_namespace;";
            default:
                return "show tables;";
        }
    }


}
