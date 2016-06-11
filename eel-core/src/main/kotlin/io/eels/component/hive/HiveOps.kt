package io.eels.component.hive

import io.eels.Constants
import io.eels.util.Logging
import io.eels.Partition
import io.eels.PartitionPart
import io.eels.schema.Column
import io.eels.schema.Schema
import io.eels.util.Option
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hive.metastore.IMetaStoreClient
import org.apache.hadoop.hive.metastore.TableType
import org.apache.hadoop.hive.metastore.api.FieldSchema
import org.apache.hadoop.hive.metastore.api.SerDeInfo
import org.apache.hadoop.hive.metastore.api.StorageDescriptor
import org.apache.hadoop.hive.metastore.api.Table
import org.apache.hadoop.hive.metastore.api.Partition as HivePartition

class HiveOps(val client: IMetaStoreClient) : Logging {

  // returns all the partitions for the given db:table
  fun partitions(dbName: String, tableName: String): List<Partition> {
    // returns hive partition names, which are in the format key1=value1/key2=value2
    val names = client.listPartitionNames(dbName, tableName, Short.MAX_VALUE)
    // should be at least one = for each partition as every partition must have at least one key value pair
    assert(names.all { it.contains("=") })
    return names.map { Partition.parsePath(it) }
  }

//  /**
//   * Returns a map of all partition keys to their values.
//   * This operation is optimized, in that it does not need to scan files, but can retrieve the information
//   * directly from the hive metastore.
//   */
//  fun partitionMap(dbName: String, tableName: String): Map<String, List<String>> =
//    client.listPartitionNames(dbName, tableName, Short.MAX_VALUE)
//        .flatMap { Partition(it).parts }
//        .groupBy { it.key }
//        .map { key, values -> Pair(key, values.map { it.value }) }
//
//  /**
//   * Returns all partition values for the given partition keys.
//   * This operation is optimized, in that it does not need to scan files, but can retrieve the information
//   * directly from the hive metastore.
//   */
//  fun partitionValues(dbName: String, tableName: String, keys: List<String>): List<PartitionPartValues> =
//    partitionMap(dbName, tableName).collect { case (key, values) if keys contains key => values }.toList

//  /**
//   * Returns all partition values for a given partition key.
//   * This operation is optimized, in that it does not need to scan files, but can retrieve the information
//   * directly from the hive metastore.
//   */
//  fun partitionValues(dbName: String, tableName: String, key: String): List<String> =
//      partitionMap(dbName, tableName).getOrElse(key, { listOf() })

  /**
   * Creates a new partition in Hive in the given database:table in the default location, which will be the
   * partition key values as a subdirectory of the table location. The values for the serialzation formats are
   * taken from the values for the table.
   */
  fun createPartition(dbName: String, tableName: String, partition: Partition): Unit {
    val table = client.getTable(dbName, tableName)
    val location = Path(table.sd.location, partition.name())
    createPartition(dbName, tableName, partition, location)
  }

  /**
   * Creates a new partition in Hive in the given database:table. The location of the partition must be
   * specified. If you want to use the default location then use the other variant that doesn't require the
   * location path. The values for the serialzation formats are taken from the values for the table.
   */
  fun createPartition(dbName: String, tableName: String, partition: Partition, location: Path): Unit {

    // we fetch the table so we can copy the serde/format values from the table. It makes no sense
    // to store a partition with different serialization formats to other partitions.
    val table = client.getTable(dbName, tableName)
    val sd = StorageDescriptor(table.sd)
    sd.location = location.toString()

    val hivePartition = HivePartition(
        partition.values(), // the hive partition values are the actual values of the partition parts
        dbName,
        tableName,
        createTimeAsInt(),
        0,
        sd,
        mapOf()
    )

    client.add_partition(hivePartition)
  }

  fun hivePartitions(dbName: String, tableName: String): List<org.apache.hadoop.hive.metastore.api.Partition> =
      client.listPartitions(dbName, tableName, Short.MAX_VALUE)

  fun createTimeAsInt(): Int = (System.currentTimeMillis() / 1000).toInt()

  fun partitionKeys(dbName: String, tableName: String): List<FieldSchema> =
      client.getTable(dbName, tableName).partitionKeys

  fun partitionKeyNames(dbName: String, tableName: String): List<String> = partitionKeys(dbName, tableName).map { it.name }

  fun tableExists(databaseName: String, tableName: String): Boolean = client.tableExists(databaseName, tableName)

  fun tableFormat(dbName: String, tableName: String): String = client.getTable(dbName, tableName).sd.inputFormat

  fun location(dbName: String, tableName: String): String = client.getTable(dbName, tableName).sd.location

  fun tablePath(dbName: String, tableName: String): Path = Path(location(dbName, tableName))

  fun partitionPath(dbName: String, tableName: String, parts: List<PartitionPart>): Path =
      partitionPath(parts, tablePath(dbName, tableName))

  fun partitionPath(parts: List<PartitionPart>, tablePath: Path): Path =
      Path(partitionPathString(parts, tablePath))

  fun partitionPathString(parts: List<PartitionPart>, tablePath: Path): String =
      tablePath.toString() + "/" + parts.map { it.unquoted() }.joinToString("/")

  // Returns the eel schema for the hive db:table
  fun schema(dbName: String, tableName: String): Schema {
    val table = client.getTable(dbName, tableName)

    // hive columns are always nullable, and hive partitions are never nullable
    val cols = table.sd.cols.map { HiveSchemaFns.fromHiveField(it, true) }
    val partitions = table.partitionKeys.map { HiveSchemaFns.fromHiveField(it, false) }

    val columns = cols.plus(partitions)
    return Schema(columns)
  }

  /**
   * Adds this column to the hive schema. This is schema evolution.
   * The column must be marked as nullable and cannot have the same name as an existing column.
   */
  fun addColumn(dbName: String, tableName: String, column: Column): Unit {
    val table = client.getTable(dbName, tableName)
    val sd = table.sd
    sd.addToCols(HiveSchemaFns.toHiveField(column))
    client.alter_table(dbName, tableName, table)
  }

  // creates (if not existing) the partition for the given partition parts
  fun partitionExists(dbName: String,
                      tableName: String,
                      parts: List<PartitionPart>): Boolean {

    val partitionName = parts.map { it.unquoted() }.joinToString ("/")
    logger.debug("Checking if partition exists '$partitionName'")

    return try {
      client.getPartition(dbName, tableName, partitionName) != null
    } catch(t: Throwable) {
      false
    }
  }

//  fun applySpec(spec: HiveSpec, overwrite: Boolean): Unit {
//    spec.tables().fore {
//      val schemas = HiveSpecFn.toSchemas(spec)
//      createTable(spec.dbName,
//        table.tableName,
//        schemas(table.tableName),
//        table.partitionKeys,
//        HiveFormat.fromInputFormat(table.inputFormat),
//        Map.empty,
//        TableType.MANAGED_TABLE,
//        None,
//        overwrite
//      )
//    }
//  }

  // creates (if not existing) the partition for the given partition parts
  fun createPartitionIfNotExists(dbName: String,
                                 tableName: String,
                                 parts: List<PartitionPart>): Unit {
    val partitionName = parts.map { it.unquoted() }.joinToString("/")
    logger.debug("Ensuring partition exists '$partitionName'")
    val exists = try {
      client.getPartition(dbName, tableName, partitionName) != null
    } catch(t: Throwable) {
      false
    }

    if (!exists) {

      val path = partitionPath(dbName, tableName, parts)
      logger.debug("Creating partition '$partitionName' at $path")

      val partition = Partition(parts.toList())
      createPartition(dbName, tableName, partition)
    }
  }

  fun createTable(databaseName: String,
                  tableName: String,
                  schema: Schema,
                  partitionKeys: List<String>,
                  format: HiveFormat = HiveFormat.Text,
                  props: Map<String, String> = emptyMap(),
                  tableType: TableType = TableType.MANAGED_TABLE,
                  location: Option<String> = Option.None,
                  overwrite: Boolean = false): Boolean {
    for (partitionKey in partitionKeys) {
      if (schema.contains(partitionKey)) {
        throw IllegalArgumentException("Schema must define all partition keys but it does not define $partitionKey")
      }
    }

    if (overwrite) {
      logger.debug("Removing table if exists (overwrite mode = true)")
      if (tableExists(databaseName, tableName)) {
        logger.debug("Table exists, it will be dropped")
        client.dropTable(databaseName, tableName, true, true, true)
      } else {
        logger.debug("Table does not exist")
      }
    }

    return if (!tableExists(databaseName, tableName)) {
      logger.info("Creating table $databaseName.$tableName with partitionKeys=${partitionKeys.joinToString(",")}")

      // we will normalize all our columns as lower case, which is how hive treats them
      val lowerPartitionKeys = partitionKeys.map { it.toLowerCase() }
      val lowerColumns = schema.columns.map { it.toLowerCase() }

      val sd = StorageDescriptor()

      // hive expects that the table fields will not contain partition keys
      sd.cols = lowerColumns.filterNot { lowerPartitionKeys.contains(it.name) }.map { HiveSchemaFns.toHiveField(it) }
      sd.serdeInfo = SerDeInfo(
          null,
          format.serdeClass(),
          mutableMapOf(Pair("serialization.format", "1"))
      )
      sd.inputFormat = format.inputFormatClass()
      sd.outputFormat = format.outputFormatClass()
      location.forEach { sd.location = it }

      val table = Table()
      table.dbName = databaseName
      table.tableName = tableName
      table.createTime = createTimeAsInt()
      table.sd = sd
      // todo support non string partitions
      table.partitionKeys = lowerPartitionKeys.map { FieldSchema(it, "string", null) }
      table.tableType = tableType.name

      table.putToParameters("generated_by", "eel_" + Constants.Version)
      if (tableType == TableType.EXTERNAL_TABLE)
        table.putToParameters("EXTERNAL", "TRUE")
      props.forEach { table.putToParameters(it.key, it.value) }

      client.createTable(table)
      logger.info("Table created $databaseName.$tableName")
      true
    } else {
      false
    }
  }
}