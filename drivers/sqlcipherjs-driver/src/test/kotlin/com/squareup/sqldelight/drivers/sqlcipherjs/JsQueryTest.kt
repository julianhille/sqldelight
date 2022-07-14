package com.squareup.sqldelight.drivers.sqljs

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.sqljs.initSqlDriver
import app.cash.sqldelight.internal.Atomic
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsQueryTest {

  private val mapper = { cursor: SqlCursor ->
    TestData(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
    )
  }

  private val schema = object : SqlSchema {
    override val version: Int = 1

    override fun create(driver: SqlDriver): QueryResult<Unit> {
      driver.execute(
        null,
        """
              CREATE TABLE test (
                id INTEGER NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
               );
        """.trimIndent(),
        0,
      )
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Int,
      newVersion: Int,
    ): QueryResult<Unit> {
      // No-op.
      return QueryResult.Unit
    }
  }

  private lateinit var driverPromise: Promise<SqlDriver>

  @BeforeTest
  fun setup() {
    driverPromise = initSqlDriver().then {
      schema.create(it)
      it
    }
  }

  @AfterTest
  fun tearDown() {
    driverPromise.then { it.close() }
  }

  @Test fun executeAsOne() = driverPromise.then { driver ->

    val data1 = TestData(1, "val1")
    driver.insertTestData(data1)

    assertEquals(data1, driver.testDataQuery().executeAsOne())
  }

  @Test fun executeAsOneTwoTimes() = driverPromise.then { driver ->

    val data1 = TestData(1, "val1")
    driver.insertTestData(data1)

    val query = driver.testDataQuery()

    assertEquals(query.executeAsOne(), query.executeAsOne())
  }

  @Test fun executeAsOneThrowsNpeForNoRows() = driverPromise.then { driver ->
    assertFailsWith<NullPointerException> {
      driver.testDataQuery().executeAsOne()
    }
  }

  @Test fun executeAsOneThrowsIllegalStateExceptionForManyRows() = driverPromise.then { driver ->
    assertFailsWith<IllegalStateException> {
      driver.insertTestData(TestData(1, "val1"))
      driver.insertTestData(TestData(2, "val2"))

      driver.testDataQuery().executeAsOne()
    }
  }

  @Test fun executeAsOneOrNull() = driverPromise.then { driver ->

    val data1 = TestData(1, "val1")
    driver.insertTestData(data1)

    val query = driver.testDataQuery()
    assertEquals(data1, query.executeAsOneOrNull())
  }

  @Test fun executeAsOneOrNullReturnsNullForNoRows() = driverPromise.then { driver ->
    assertNull(driver.testDataQuery().executeAsOneOrNull())
  }

  @Test fun executeAsOneOrNullThrowsIllegalStateExceptionForManyRows() = driverPromise.then { driver ->
    assertFailsWith<IllegalStateException> {
      driver.insertTestData(TestData(1, "val1"))
      driver.insertTestData(TestData(2, "val2"))

      driver.testDataQuery().executeAsOneOrNull()
    }
  }

  @Test fun executeAsList() = driverPromise.then { driver ->

    val data1 = TestData(1, "val1")
    val data2 = TestData(2, "val2")

    driver.insertTestData(data1)
    driver.insertTestData(data2)

    assertEquals(listOf(data1, data2), driver.testDataQuery().executeAsList())
  }

  @Test fun executeAsListForNoRows() = driverPromise.then { driver ->
    assertTrue(driver.testDataQuery().executeAsList().isEmpty())
  }

  @Test fun notifyDataChangedNotifiesListeners() = driverPromise.then { driver ->

    val notifies = Atomic(0)
    val query = driver.testDataQuery()
    val listener = object : Query.Listener {
      override fun queryResultsChanged() {
        notifies.increment()
      }
    }

    query.addListener(listener)
    assertEquals(0, notifies.get())

    driver.notifyListeners(arrayOf("test"))
    assertEquals(1, notifies.get())
  }

  @Test fun removeListenerActuallyRemovesListener() = driverPromise.then { driver ->

    val notifies = Atomic(0)
    val query = driver.testDataQuery()
    val listener = object : Query.Listener {
      override fun queryResultsChanged() {
        notifies.increment()
      }
    }

    query.addListener(listener)
    query.removeListener(listener)
    driver.notifyListeners(arrayOf("test"))
    assertEquals(0, notifies.get())
  }

  private fun SqlDriver.insertTestData(testData: TestData) {
    execute(1, "INSERT INTO test VALUES (?, ?)", 2) {
      bindLong(0, testData.id)
      bindString(1, testData.value)
    }
  }

  private fun SqlDriver.testDataQuery(): Query<TestData> {
    return object : Query<TestData>(mapper) {
      override fun <R> execute(mapper: (SqlCursor) -> R): QueryResult<R> {
        return executeQuery(0, "SELECT * FROM test", mapper, 0, null)
      }

      override fun addListener(listener: Listener) {
        addListener(listener, arrayOf("test"))
      }

      override fun removeListener(listener: Listener) {
        removeListener(listener, arrayOf("test"))
      }
    }
  }

  private data class TestData(val id: Long, val value: String)
}

// Not actually atomic, the type needs to be as the listeners get frozen.
private fun Atomic<Int>.increment() = set(get() + 1)
