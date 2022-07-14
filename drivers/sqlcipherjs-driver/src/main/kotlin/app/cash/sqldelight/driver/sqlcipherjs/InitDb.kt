package app.cash.sqldelight.driver.sqlcipherjs

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

operator fun InitStatementJsStatic.invoke(): Statement = createInstance(this)
operator fun InitDatabaseJsStatic.invoke(): Database = createInstance(this)
operator fun InitDatabaseJsStatic.invoke(data: Array<Number>): Database = createInstance(this, data)
operator fun InitDatabaseJsStatic.invoke(data: Uint8Array): Database = createInstance(this, data)
operator fun InitSqlJsStatic.invoke(): Promise<SqlJsStatic> = asDynamic()()
operator fun InitSqlJsStatic.invoke(config: Config?): Promise<SqlJsStatic> = asDynamic()(config)

@JsNonModule
@JsModule("sql.js")
fun (): Database
