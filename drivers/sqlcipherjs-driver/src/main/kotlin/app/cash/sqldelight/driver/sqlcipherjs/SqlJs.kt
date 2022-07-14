@file:JsModule("better-sqlite3-multiple-ciphers")
@file:JsNonModule
//@file:Suppress("INTERFACE_WITH_SUPERCLASS", "OVERRIDING_FINAL_MEMBER", "RETURN_TYPE_MISMATCH_ON_OVERRIDE", "CONFLICTING_OVERLOADS", "EXTERNAL_DELEGATION")
package app.cash.sqldelight.driver.sqlcipherjs

import org.khronos.webgl.Uint8Array

import kotlinx.coroutines.promise

external class SqliteError: Error {
  var name: String;
  var code: String;
  var message: String;
}

data class Options {
  var readonly: Boolean // = false
  val fileMustExist: Boolean // = false
  val timeout: Int // = 5000
  val verbose: Boolean // = false
  val nativeBindingPath: Boolean // = false
}

external data class PragmaOptions {
  var simple: Boolean?;
}

external interface BackupOptions
external interface Buffer
external interface SerializeOptions
external interface UserDefinedFunctionOption;
external interface AggregateOptions;
external interface VirtualTableOptions;
external interface EntryPointOptions;
external interface Row;

class ColumnResult {
  var name: String
  var column: String
  var table: String
  var database: String
  var type: String
}

class Result {
  val changes: Int?
  val lastInsertRowid: Int?
}

class IteratorResult {
  var value: Map<String, Any>?
  var done: Boolean
}

class StatementIterator {
  fun next(): IteratorResult
  var statement: Statement
  fun `return`(): IteratorResult
}

open external class Statement {
  var busy: Boolean;
  val reader: Boolean;
  val readonly: Boolean;
  val source: String;

  val database: Database;

  fun pluck(state: Boolean?): this
  fun expand(state: Boolean?): this
  fun raw(state: Boolean?): this
  fun get(vararg parameters: Any): Row?
  fun all(vararg parameters: Any): Array<Row>
  fun run(vararg parameters: Any): Result
  fun iterate(vararg parameters: Any): Database.Companion.StatementIterator
  fun bind(vararg parameters: Any): this
  fun columns(): Array<ColumnResult>
}

public open external class Database() {
  var open: Boolean;
  var inTransaction: Boolean;
  var name: String;
  var memory: Boolean;
  var readonly: Boolean;

  public constructor(memory: Array<Byte>, dbOptions: Options?)
  public constructor(path: String)
  public constructor(path: String, dbOptions: Options?)

  fun prepare(sql: String) : Statement;
  fun exec(sql: String) : this;
  fun close (): this;
  fun transaction (func: () -> Unit): () -> Unit;
  fun pragma (sql: String, option: PragmaOptions?): Array<dynamic /* Number | String | Boolean | Nothing? */>

  fun backup(destination: String, options: BackupOptions?): promise;
  fun serialize (options: SerializeOptions?): Buffer
  fun function(name: String, options: UserDefinedFunctionOption, function: () -> dynamic): this
  fun function(name: String, function: () -> dynamic): this
  fun aggregate(name: String, options: AggregateOptions): this
  fun table(name: String, definition: VirtualTableOptions): this
  fun loadExtension(path: String, entryPoint: EntryPointOptions?): this
}

