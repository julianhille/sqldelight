package app.cash.sqldelight.postgresql.integration

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.OptimisticLockException
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PostgreSqlTest {
  val conn = DriverManager.getConnection("jdbc:tc:postgresql:9.6.8:///my_db")
  val driver = object : JdbcDriver() {
    override fun getConnection() = conn
    override fun closeConnection(connection: Connection) = Unit
    override fun addListener(listener: Query.Listener, queryKeys: Array<String>) = Unit
    override fun removeListener(listener: Query.Listener, queryKeys: Array<String>) = Unit
    override fun notifyListeners(queryKeys: Array<String>) = Unit
  }
  val database = MyDatabase(driver)

  @Before fun before() {
    MyDatabase.Schema.create(driver)
  }

  @After fun after() {
    conn.close()
  }

  @Test fun simpleSelect() {
    database.dogQueries.insertDog("Tilda", "Pomeranian", 1)
    assertThat(database.dogQueries.selectDogs().executeAsOne())
      .isEqualTo(
        Dog(
          name = "Tilda",
          breed = "Pomeranian",
          is_good = 1,
        ),
      )
  }

  @Test fun booleanSelect() {
    database.dogQueries.insertDog("Tilda", "Pomeranian", 1)
    assertThat(database.dogQueries.selectGoodDogs(true).executeAsOne())
      .isEqualTo(
        Dog(
          name = "Tilda",
          breed = "Pomeranian",
          is_good = 1,
        ),
      )
  }

  @Test fun returningInsert() {
    assertThat(database.dogQueries.insertAndReturn("Tilda", "Pomeranian", 1).executeAsOne())
      .isEqualTo(
        Dog(
          name = "Tilda",
          breed = "Pomeranian",
          is_good = 1,
        ),
      )
  }

  @Test fun testDates() {
    assertThat(
      database.datesQueries.insertDate(
        date = LocalDate.of(2020, 1, 1),
        time = LocalTime.of(21, 30, 59, 10000),
        timestamp = LocalDateTime.of(2020, 1, 1, 21, 30, 59, 10000),
        timestamp_with_timezone = OffsetDateTime.of(1980, 4, 9, 20, 15, 45, 0, ZoneOffset.ofHours(0)),
      ).executeAsOne(),
    )
      .isEqualTo(
        Dates(
          date = LocalDate.of(2020, 1, 1),
          time = LocalTime.of(21, 30, 59, 10000),
          timestamp = LocalDateTime.of(2020, 1, 1, 21, 30, 59, 10000),
          timestamp_with_timezone = OffsetDateTime.of(1980, 4, 9, 20, 15, 45, 0, ZoneOffset.ofHours(0)),
        ),
      )
  }

  @Test fun testDateTrunc() {
    database.datesQueries.insertDate(
      date = LocalDate.of(2020, 1, 1),
      time = LocalTime.of(21, 30, 59, 10000),
      timestamp = LocalDateTime.of(2020, 1, 1, 21, 30, 59, 10000),
      timestamp_with_timezone = OffsetDateTime.of(1980, 4, 9, 20, 15, 45, 0, ZoneOffset.ofHours(0)),
    ).executeAsOne()

    assertThat(
      database.datesQueries.selectDateTrunc().executeAsOne(),
    )
      .isEqualTo(
        SelectDateTrunc(
          date_trunc = LocalDateTime.of(2020, 1, 1, 21, 0, 0, 0),
          date_trunc_ = OffsetDateTime.of(1980, 4, 9, 20, 0, 0, 0, ZoneOffset.ofHours(0)),
        ),
      )
  }

  @Test fun testSerial() {
    database.run {
      oneEntityQueries.transaction {
        oneEntityQueries.insert("name1")
        oneEntityQueries.insert("name2")
        oneEntityQueries.insert("name3")
      }
      assertThat(oneEntityQueries.selectAll().executeAsList().map { it.id }).containsExactly(1, 2, 3)
    }
  }

  @Test fun testArrays() {
    with(database.arraysQueries.insertAndReturn(arrayOf(1, 2), arrayOf("one", "two")).executeAsOne()) {
      assertThat(intArray!!.asList()).containsExactly(1, 2).inOrder()
      assertThat(textArray!!.asList()).containsExactly("one", "two").inOrder()
    }
  }

  @Test fun successfulOptimisticLock() {
    with(database.withLockQueries) {
      val row = insertText("sup").executeAsOne()

      updateText(
        id = row.id,
        version = row.version,
        text = "sup2",
      )

      assertThat(selectForId(row.id).executeAsOne().text).isEqualTo("sup2")
    }
  }

  @Test fun unsuccessfulOptimisticLock() {
    with(database.withLockQueries) {
      val row = insertText("sup").executeAsOne()

      updateText(
        id = row.id,
        version = row.version,
        text = "sup2",
      )

      try {
        updateText(
          id = row.id,
          version = row.version,
          text = "sup3",
        )
        Assert.fail()
      } catch (e: OptimisticLockException) { }
    }
  }
}
