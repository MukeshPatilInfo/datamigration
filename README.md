# Data Migration Analysis Utility

## Requirements

- Java 17 JDK (not only a JRE)
- PostgreSQL database with the ENOVIA and PDM source tables already created
- PostgreSQL JDBC driver 42.7.5, included in the Eclipse delivery under `lib\`
- Eclipse IDE for Java Developers 2024-xx or newer

## Eclipse setup

1. Extract the delivery ZIP and open Eclipse.
2. Select **File > Import > Maven > Existing Maven Projects** and select the extracted
   `DataMigration-Eclipse-Java-1.0.0` directory. Eclipse downloads the PostgreSQL driver from
   `pom.xml`.
3. If Maven dependency download is unavailable, right-click the project, choose
   **Build Path > Configure Build Path > Libraries > Add External JARs**, and select
   `lib\postgresql-42.7.5.jar`.
4. Copy `config\application.properties.example` to `config\application.properties`, then set
   `db.url`, `db.user`, and `db.password`. Do not commit this local file.
5. Run `sql\001_create_match_tracking.sql` once after the source tables have been created.
6. In Eclipse, open
   `src\main\java\com\datamigration\analysis\DataMigrationAnalysisUtility.java`, select
   **Run As > Java Application**, and set the program argument to
   `config\application.properties`.

The project uses Java 17. In Eclipse, set **Window > Preferences > Java > Installed JREs** to a
Java 17 JDK if Eclipse does not select it automatically.

## Configuration

Configure the database connection, table names, report directory, and output columns in
`config\application.properties`. Start from `config\application.properties.example`.

Build and run:

```powershell
mvn package
java -jar target\data-migration-analysis-utility-1.0.0.jar config\application.properties
```

The utility executes Rules 1 through 15 in spreadsheet order. A PDM record is inserted into
`dm_data_match_rules` as soon as it is reported, so it is excluded from every later rule and
report. CSV reports are written below `reports.directory`.
Each rule writes a separate CSV for every vault listed in `report.vaults`; `cad_primary` from
each PDM source table is used as that record's vault.

## Python implementation

The Python utility uses the same configuration file, output columns, SQL table names, and matching
sequence as the Java version.

```powershell
py -m pip install -r python\requirements.txt
py python\data_migration_analysis.py config\application.properties
```
