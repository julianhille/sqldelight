class BetterJsSqlDriver (var configuration: DatabaseConfiguration ): SqlDriver {
  private val db = jsSQL(configuration.dbPath(), js("{verbose: console.log}"))
  private val statements = mutableMapOf<Int, jsSQL.Companion.Statement>()
  private var transaction: Transacter.Transaction? = null


  init {
    if (configuration.journalMode ) db.pragma("journal_mode = WAL", PragmaOption())
    if (configuration.key?.isNotBlank() == true) db.pragma("journal_mode = WAL", PragmaOption())
    migrateIfNeeded(configuration.create, configuration.upgrade, configuration.schema.version)
  }

  override fun close() = db.close()

  override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?) {
    createOrGetStatement(identifier, sql).run {
      bind(binders)
      run()
    }
  }

  private fun jsSQL.Companion.Statement.bind(binders: (SqlPreparedStatement.() -> Unit)?) = binders?.let {
    val bound = B3Statement()
    binders(bound)
    bind(bound.parameters.toTypedArray())
  }

  override fun executeQuery(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?
  ): SqlCursor {
    return createOrGetStatement(identifier, sql).run {
      bind(binders)
      B3Cursor(iterate())
    }
  }


  override fun newTransaction(): Transacter.Transaction {
    val enclosing = transaction
    val transaction = Transaction(enclosing)
    this.transaction = transaction
    if (enclosing == null) {
      db.exec("BEGIN TRANSACTION")
    }
    return transaction
  }

  override fun currentTransaction() = transaction

  private inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?
  ) : Transacter.Transaction() {
    override fun endTransaction(successful: Boolean) {
      if (enclosingTransaction == null) {
        if (successful) {
          db.exec("END TRANSACTION")
        } else {
          db.exec("ROLLBACK TRANSACTION")
        }
      }
      transaction = enclosingTransaction
    }
  }

  private fun createOrGetStatement(identifier: Int?, sql: String): jsSQL.Companion.Statement = db.prepare(sql)

  fun getVersion(): Int = (db.pragma("user_version",  PragmaOption()) as? Double)?.toInt() ?: 0

  private fun setVersion(version: Int): Unit {
    db.pragma("user_version=${version}", PragmaOption())
  }


  fun migrateIfNeeded(
    create: (SqlDriver) -> Unit,
    upgrade: (SqlDriver, Int, Int) -> Unit,
    version: Int
  ) {
    // TODO: Needs transaction
    val initialVersion = getVersion()
    if (initialVersion == 0) {
      create(this)
      setVersion(version)
    } else if (initialVersion != version) {
      if (initialVersion > version)
        throw IllegalStateException("Database version $initialVersion newer than config version $version")

      upgrade(this, initialVersion, version)
      setVersion(version)
    }

  }

}


class B3Statement: SqlPreparedStatement {
  val parameters = mutableListOf<Any?>()
  override fun bindBytes(index: Int, bytes: ByteArray?) {
    parameters.add(bytes?.toTypedArray())
  }

  override fun bindLong(index: Int, long: Long?) {
    // We convert Long to Double because Kotlin's Double is mapped to JS number
    // whereas Kotlin's Long is implemented as a JS object
    parameters.add(long?.toDouble())
  }

  override fun bindDouble(index: Int, double: Double?) {
    parameters.add(double)
  }

  override fun bindString(index: Int, string: String?) {
    parameters.add(string)
  }

  fun bindBoolean(index: Int, boolean: Boolean?) {
    parameters.add(
      when (boolean) {
        null -> null
        true -> 1.0
        false -> 0.0
      }
    )
  }
}

class B3Cursor(private val statementIterator: jsSQL.Companion.StatementIterator): SqlCursor {
  var lastResult: jsSQL.Companion.IteratorResult? = null
  var columns: List<String> = listOf<String>()


  private fun getIndex(index: Int):  dynamic? /* Number | String | Uint8Array | Nothing? */ {
    val name = columns[index]
    val value = lastResult?.value ?: throw NoSuchElementException()
    return js("value[name]")
  }

  override fun close() {
    statementIterator.`return`()
  }

  override fun getString(index: Int): String? = getIndex(index)
  override fun getLong(index: Int): Long? = (getIndex(index) as? Double)?.toLong()
  override fun getBytes(index: Int): ByteArray? = (getIndex(index) as? Uint8Array)?.let {
    Int8Array(it.buffer).unsafeCast<ByteArray>()
  }
  override fun getDouble(index: Int): Double? = getIndex(index)

  fun getBoolean(index: Int): Boolean? {
    val double = (getIndex(index) as? Double)
    if (double == null) return null
    else return double.toLong() == 1L
  }

  override fun next(): Boolean {
    if (columns.size == 0) {
      columns = statementIterator.statement.columns().map {
        it.name
      }
    }
    lastResult = statementIterator.next()
    console.log(lastResult)

    js("console.trace()")
    return lastResult!!.done.not()
  }



}


actual class SqlDriverFactory {
  actual suspend fun build(): SqlDriver {
    val configuration = DatabaseConfiguration(
      name = Database.filename,
      path = "/tmp/satr/db.db",
      schema = LFTSchema,
      create = { connection ->
        LFTSchema.create(connection)
      },
      upgrade = { connection, oldVersion, newVersion ->
        LFTSchema.migrate(
          driver = connection,
          oldVersion = oldVersion,
          newVersion = newVersion
        )
      }
    )


    val driver: SqlDriver = BetterJsSqlDriver(
      configuration
    )


    return driver
  }
}