package com.squareup.sqldelight.driver.test

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.internal.Atomic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class QueryTest {
  private val mapper = { cursor: SqlCursor ->
    TestData(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
    )
  }

  private lateinit var driver: SqlDriver

  abstract fun setupDatabase(schema: SqlSchema): SqlDriver

  @BeforeTest fun setup() {
    driver = setupDatabase(
      schema = object : SqlSchema {
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
      },
    )
  }

  @AfterTest fun tearDown() {
    driver.close()
  }

  @Test fun executeAsOne() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    assertEquals(data1, testDataQuery().executeAsOne())
  }

  @Test fun executeAsOneTwoTimes() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    val query = testDataQuery()

    assertEquals(query.executeAsOne(), query.executeAsOne())
  }

  @Test fun executeAsOneThrowsNpeForNoRows() {
    try {
      testDataQuery().executeAsOne()
      throw AssertionError("Expected an IllegalStateException")
    } catch (ignored: NullPointerException) {
    }
  }

  @Test fun executeAsOneThrowsIllegalStateExceptionForManyRows() {
    try {
      insertTestData(TestData(1, "val1"))
      insertTestData(TestData(2, "val2"))

      testDataQuery().executeAsOne()
      throw AssertionError("Expected an IllegalStateException")
    } catch (ignored: IllegalStateException) {
    }
  }

  @Test fun executeAsOneOrNull() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    val query = testDataQuery()
    assertEquals(data1, query.executeAsOneOrNull())
  }

  @Test fun executeAsOneOrNullReturnsNullForNoRows() {
    assertNull(testDataQuery().executeAsOneOrNull())
  }

  @Test fun executeAsOneOrNullThrowsIllegalStateExceptionForManyRows() {
    try {
      insertTestData(TestData(1, "val1"))
      insertTestData(TestData(2, "val2"))

      testDataQuery().executeAsOneOrNull()
      throw AssertionError("Expected an IllegalStateException")
    } catch (ignored: IllegalStateException) {
    }
  }

  @Test fun executeAsList() {
    val data1 = TestData(1, "val1")
    val data2 = TestData(2, "val2")

    insertTestData(data1)
    insertTestData(data2)

    assertEquals(listOf(data1, data2), testDataQuery().executeAsList())
  }

  @Test fun executeAsListForNoRows() {
    assertTrue(testDataQuery().executeAsList().isEmpty())
  }

  @Test fun notifyDataChangedNotifiesListeners() {
    val notifies = Atomic(0)
    val query = testDataQuery()
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

  @Test fun removeListenerActuallyRemovesListener() {
    val notifies = Atomic(0)
    val query = testDataQuery()
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

  private fun insertTestData(testData: TestData) {
    driver.execute(1, "INSERT INTO test VALUES (?, ?)", 2) {
      bindLong(0, testData.id)
      bindString(1, testData.value)
    }
  }

  private fun testDataQuery(): Query<TestData> {
    return object : Query<TestData>(mapper) {
      override fun <R> execute(mapper: (SqlCursor) -> R): QueryResult<R> {
        return driver.executeQuery(0, "SELECT * FROM test", mapper, 0, null)
      }

      override fun addListener(listener: Listener) {
        driver.addListener(listener, arrayOf("test"))
      }

      override fun removeListener(listener: Listener) {
        driver.removeListener(listener, arrayOf("test"))
      }
    }
  }

  private data class TestData(val id: Long, val value: String)
}

// Not actually atomic, the type needs to be as the listeners get frozen.
private fun Atomic<Int>.increment() = set(get() + 1)
