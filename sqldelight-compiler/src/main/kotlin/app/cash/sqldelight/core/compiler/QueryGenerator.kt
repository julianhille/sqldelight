package app.cash.sqldelight.core.compiler

import app.cash.sqldelight.core.compiler.integration.javadocText
import app.cash.sqldelight.core.compiler.model.BindableQuery
import app.cash.sqldelight.core.compiler.model.NamedMutator
import app.cash.sqldelight.core.compiler.model.NamedQuery
import app.cash.sqldelight.core.lang.DRIVER_NAME
import app.cash.sqldelight.core.lang.MAPPER_NAME
import app.cash.sqldelight.core.lang.PREPARED_STATEMENT_TYPE
import app.cash.sqldelight.core.lang.encodedJavaType
import app.cash.sqldelight.core.lang.preparedStatementBinder
import app.cash.sqldelight.core.lang.util.childOfType
import app.cash.sqldelight.core.lang.util.columnDefSource
import app.cash.sqldelight.core.lang.util.findChildrenOfType
import app.cash.sqldelight.core.lang.util.isArrayParameter
import app.cash.sqldelight.core.lang.util.range
import app.cash.sqldelight.core.lang.util.rawSqlText
import app.cash.sqldelight.core.lang.util.sqFile
import app.cash.sqldelight.core.psi.SqlDelightStmtClojureStmtList
import app.cash.sqldelight.dialect.api.IntermediateType
import com.alecstrong.sql.psi.core.psi.SqlBinaryEqualityExpr
import com.alecstrong.sql.psi.core.psi.SqlBindExpr
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.alecstrong.sql.psi.core.psi.SqlTypes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.NameAllocator

abstract class QueryGenerator(
  private val query: BindableQuery,
) {
  protected val dialect = query.statement.sqFile().dialect
  protected val treatNullAsUnknownForEquality = query.statement.sqFile().treatNullAsUnknownForEquality
  protected val generateAsync = query.statement.sqFile().generateAsync

  /**
   * Creates the block of code that prepares [query] as a prepared statement and binds the
   * arguments to it. This code block does not make any use of class fields, and only populates a
   * single variable [STATEMENT_NAME]
   *
   * val numberIndexes = createArguments(count = number.size)
   * val statement = database.prepareStatement("""
   *     |SELECT *
   *     |FROM player
   *     |WHERE number IN $numberIndexes
   *     """.trimMargin(), SqlPreparedStatement.Type.SELECT, 1 + (number.size - 1))
   * number.forEachIndexed { index, number ->
   *     check(this is SqlCursorSubclass)
   *     statement.bindLong(index + 2, number)
   *     }
   */
  protected fun executeBlock(): CodeBlock {
    val result = CodeBlock.builder()

    if (query.statement is SqlDelightStmtClojureStmtList) {
      if (query is NamedQuery) {
        result.add("return transactionWithResult {\n").indent()
      } else {
        result.add("transaction {\n").indent()
      }
      query.statement.findChildrenOfType<SqlStmt>().forEachIndexed { index, statement ->
        result.add(executeBlock(statement, query.idForIndex(index)))
      }
      result.unindent().add("}\n")
    } else {
      result.add(executeBlock(query.statement, query.id))
    }

    return result.build()
  }

  private fun executeBlock(
    statement: PsiElement,
    id: Int,
  ): CodeBlock {
    val dialectPreparedStatementType = if (generateAsync) dialect.asyncRuntimeTypes.preparedStatementType else dialect.runtimeTypes.preparedStatementType

    val result = CodeBlock.builder()

    val positionToArgument = mutableListOf<Triple<Int, BindableQuery.Argument, SqlBindExpr?>>()
    val seenArgs = mutableSetOf<BindableQuery.Argument>()
    val duplicateTypes = mutableSetOf<IntermediateType>()
    query.arguments.forEach { argument ->
      if (argument.bindArgs.isNotEmpty()) {
        argument.bindArgs
          .filter { PsiTreeUtil.isAncestor(statement, it, true) }
          .forEach { bindArg ->
            if (!seenArgs.add(argument)) {
              duplicateTypes.add(argument.type)
            }
            positionToArgument.add(Triple(bindArg.node.textRange.startOffset, argument, bindArg))
          }
      } else {
        positionToArgument.add(Triple(0, argument, null))
      }
    }

    val bindStatements = CodeBlock.builder()
    val replacements = mutableListOf<Pair<IntRange, String>>()
    val argumentCounts = mutableListOf<String>()

    var needsFreshStatement = false

    val seenArrayArguments = mutableSetOf<BindableQuery.Argument>()

    val argumentNameAllocator = NameAllocator().apply {
      query.arguments.forEach { newName(it.type.name) }
    }

    // A list of [SqlBindExpr] in order of appearance in the query.
    val orderedBindArgs = positionToArgument.sortedBy { it.first }

    // The number of non-array bindArg's we've encountered so far.
    var nonArrayBindArgsCount = 0

    // A list of arrays we've encountered so far.
    val precedingArrays = mutableListOf<String>()

    val extractedVariables = mutableMapOf<IntermediateType, String>()
    // extract the variable for duplicate types, so we don't encode twice
    for (type in duplicateTypes) {
      if (type.bindArg?.isArrayParameter() == true) continue
      val encodedJavaType = type.encodedJavaType() ?: continue
      val variableName = argumentNameAllocator.newName(type.name)
      extractedVariables[type] = variableName
      bindStatements.add("val %N = $encodedJavaType\n", variableName)
    }
    // For each argument in the sql
    orderedBindArgs.forEach { (_, argument, bindArg) ->
      val type = argument.type
      // Need to replace the single argument with a group of indexed arguments, calculated at
      // runtime from the list parameter:
      // val idIndexes = id.mapIndexed { index, _ -> "?${previousArray.size + index}" }.joinToString(prefix = "(", postfix = ")")
      val offset = (precedingArrays.map { "$it.size" } + "$nonArrayBindArgsCount")
        .joinToString(separator = " + ").replace(" + 0", "")
      if (bindArg?.isArrayParameter() == true) {
        needsFreshStatement = true

        if (seenArrayArguments.add(argument)) {
          result.addStatement(
            """
            |val ${type.name}Indexes = createArguments(count = ${type.name}.size)
            """.trimMargin(),
          )
        }

        // Replace the single bind argument with the array of bind arguments:
        // WHERE id IN ${idIndexes}
        replacements.add(bindArg.range to "\$${type.name}Indexes")

        // Perform the necessary binds:
        // id.forEachIndex { index, parameter ->
        //   statement.bindLong(previousArray.size + index, parameter)
        // }
        val indexCalculator = "index + $offset".replace(" + 0", "")
        val elementName = argumentNameAllocator.newName(type.name)
        bindStatements.add(
          """
          |${type.name}.forEachIndexed { index, $elementName ->
          |  %L}
          |
          """.trimMargin(),
          type.copy(name = elementName).preparedStatementBinder(indexCalculator),
        )

        precedingArrays.add(type.name)
        argumentCounts.add("${type.name}.size")
      } else {
        nonArrayBindArgsCount += 1

        if (!treatNullAsUnknownForEquality && type.javaType.isNullable) {
          val parent = bindArg?.parent
          if (parent is SqlBinaryEqualityExpr) {
            needsFreshStatement = true

            var symbol = parent.childOfType(SqlTypes.EQ) ?: parent.childOfType(SqlTypes.EQ2)
            val nullableEquality: String
            if (symbol != null) {
              nullableEquality = "${symbol.leftWhitspace()}IS${symbol.rightWhitespace()}"
            } else {
              symbol = parent.childOfType(SqlTypes.NEQ) ?: parent.childOfType(SqlTypes.NEQ2)!!
              nullableEquality = "${symbol.leftWhitspace()}IS NOT${symbol.rightWhitespace()}"
            }

            val block = CodeBlock.of("if (${type.name} == null) \"$nullableEquality\" else \"${symbol.text}\"")
            replacements.add(symbol.range to "\${ $block }")
          }
        }

        // Binds each parameter to the statement:
        // statement.bindLong(0, id)
        bindStatements.add(type.preparedStatementBinder(offset, extractedVariables[type]))

        // Replace the named argument with a non named/indexed argument.
        // This allows us to use the same algorithm for non Sqlite dialects
        // :name becomes ?
        if (bindArg != null) {
          replacements.add(bindArg.range to "?")
        }
      }
    }

    val optimisticLock = if (query is NamedMutator.Update) {
      val columnsUpdated =
        query.update.updateStmtSubsequentSetterList.mapNotNull { it.columnName } +
          query.update.columnNameList
      columnsUpdated.singleOrNull {
        it.columnDefSource()!!.columnType.node.getChildren(null).any { it.text == "LOCK" }
      }
    } else {
      null
    }

    // Adds the actual SqlPreparedStatement:
    // statement = database.prepareStatement("SELECT * FROM test")
    val isNamedQuery = query is NamedQuery &&
      (statement == query.statement || statement == query.statement.children.filterIsInstance<SqlStmt>().last())
    if (nonArrayBindArgsCount != 0) {
      argumentCounts.add(0, nonArrayBindArgsCount.toString())
    }
    val arguments = mutableListOf<Any>(
      statement.rawSqlText(replacements),
      argumentCounts.ifEmpty { listOf(0) }.joinToString(" + "),
    )

    var binder: String

    if (argumentCounts.isEmpty()) {
      binder = ""
    } else {
      val binderLambda = CodeBlock.builder()
        .add(" {\n")
        .indent()

      if (PREPARED_STATEMENT_TYPE != dialectPreparedStatementType) {
        binderLambda.add("check(this is %T)\n", dialectPreparedStatementType)
      }

      binderLambda.add(bindStatements.build())
        .unindent()
        .add("}")
      arguments.add(binderLambda.build())
      binder = "%L"
    }
    if (generateAsync) {
      val awaiter = awaiting()

      if (isNamedQuery) {
        awaiter?.let { (bind, arg) ->
          binder += bind
          arguments.add(arg)
        }
      } else {
        binder += "%L"
        arguments.add(".await()")
      }
    }

    val statementId = if (needsFreshStatement) "null" else "$id"

    if (isNamedQuery) {
      val execute = if (query.statement is SqlDelightStmtClojureStmtList) {
        "$DRIVER_NAME.executeQuery"
      } else {
        "return $DRIVER_NAME.executeQuery"
      }
      result.addStatement(
        "$execute($statementId, %P, $MAPPER_NAME, %L)$binder",
        *arguments.toTypedArray(),
      )
    } else if (optimisticLock != null) {
      result.addStatement(
        "val result = $DRIVER_NAME.execute($statementId, %P, %L)$binder",
        *arguments.toTypedArray(),
      )
    } else {
      result.addStatement(
        "$DRIVER_NAME.execute($statementId, %P, %L)$binder",
        *arguments.toTypedArray(),
      )
    }

    if (query is NamedMutator.Update && optimisticLock != null) {
      result.addStatement(
        """
        if (result%L == 0L) throw %T(%S)
        """.trimIndent(),
        if (generateAsync) ".await()" else ".value",
        ClassName("app.cash.sqldelight.db", "OptimisticLockException"),
        "UPDATE on ${query.tablesAffected.single().name} failed because optimistic lock ${optimisticLock.name} did not match",
      )
    }

    return result.build()
  }

  private fun PsiElement.leftWhitspace(): String {
    return if (prevSibling is PsiWhiteSpace) "" else " "
  }

  private fun PsiElement.rightWhitespace(): String {
    return if (nextSibling is PsiWhiteSpace) "" else " "
  }

  protected fun addJavadoc(builder: FunSpec.Builder) {
    if (query.javadoc != null) javadocText(query.javadoc)?.let { builder.addKdoc(it) }
  }

  protected open fun awaiting(): Pair<String, String>? = "%L" to ".await()"
}
