/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.js;

import com.google.gwt.core.ext.BadPropertyValueException;
import com.google.gwt.core.ext.PropertyOracle;
import com.google.gwt.core.ext.SelectionProperty;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.dev.jjs.HasSourceInfo;
import com.google.gwt.dev.jjs.InternalCompilerException;
import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.ast.JMethod;
import com.google.gwt.dev.jjs.ast.JProgram;
import com.google.gwt.dev.jjs.impl.JavaToJavaScriptMap;
import com.google.gwt.dev.js.ast.JsArrayAccess;
import com.google.gwt.dev.js.ast.JsArrayLiteral;
import com.google.gwt.dev.js.ast.JsBinaryOperation;
import com.google.gwt.dev.js.ast.JsBinaryOperator;
import com.google.gwt.dev.js.ast.JsBlock;
import com.google.gwt.dev.js.ast.JsBooleanLiteral;
import com.google.gwt.dev.js.ast.JsCatch;
import com.google.gwt.dev.js.ast.JsContext;
import com.google.gwt.dev.js.ast.JsExprStmt;
import com.google.gwt.dev.js.ast.JsExpression;
import com.google.gwt.dev.js.ast.JsFor;
import com.google.gwt.dev.js.ast.JsFunction;
import com.google.gwt.dev.js.ast.JsInvocation;
import com.google.gwt.dev.js.ast.JsModVisitor;
import com.google.gwt.dev.js.ast.JsName;
import com.google.gwt.dev.js.ast.JsNameRef;
import com.google.gwt.dev.js.ast.JsNew;
import com.google.gwt.dev.js.ast.JsNode;
import com.google.gwt.dev.js.ast.JsNullLiteral;
import com.google.gwt.dev.js.ast.JsNumberLiteral;
import com.google.gwt.dev.js.ast.JsPostfixOperation;
import com.google.gwt.dev.js.ast.JsPrefixOperation;
import com.google.gwt.dev.js.ast.JsProgram;
import com.google.gwt.dev.js.ast.JsReturn;
import com.google.gwt.dev.js.ast.JsRootScope;
import com.google.gwt.dev.js.ast.JsStatement;
import com.google.gwt.dev.js.ast.JsStringLiteral;
import com.google.gwt.dev.js.ast.JsThrow;
import com.google.gwt.dev.js.ast.JsTry;
import com.google.gwt.dev.js.ast.JsUnaryOperation;
import com.google.gwt.dev.js.ast.JsUnaryOperator;
import com.google.gwt.dev.js.ast.JsVars;
import com.google.gwt.dev.js.ast.JsVars.JsVar;
import com.google.gwt.dev.js.ast.JsVisitor;
import com.google.gwt.dev.js.ast.JsWhile;
import com.google.gwt.dev.util.collect.Lists;
import com.google.gwt.dev.util.collect.Maps;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emulates the JS stack in order to provide useful stack traces on browers that
 * do not provide useful stack information.
 * 
 * @see com.google.gwt.core.client.impl.StackTraceCreator
 */
public class JsStackEmulator {

  private static final String PROPERTY_NAME = "compiler.stackMode";

  /**
   * Resets the global stack depth to the local stack index and top stack frame
   * after calls to Exceptions.wrap. This is created by
   * {@link EntryExitVisitor#visit(JsCatch, JsContext)}.
   */
  private class CatchStackReset extends JsModVisitor {

    /**
     * The local stackIndex variable in the function.
     */
    private final EntryExitVisitor eeVisitor;

    public CatchStackReset(EntryExitVisitor eeVisitor) {
      this.eeVisitor = eeVisitor;
    }

    @Override
    public void endVisit(JsExprStmt x, JsContext ctx) {
      // Looking for e = wrap(e);
      JsExpression expr = x.getExpression();

      if (!(expr instanceof JsBinaryOperation)) {
        return;
      }

      JsBinaryOperation op = (JsBinaryOperation) expr;
      if (!(op.getArg2() instanceof JsInvocation)) {
        return;
      }

      JsInvocation i = (JsInvocation) op.getArg2();
      JsExpression q = i.getQualifier();
      if (!(q instanceof JsNameRef)) {
        return;
      }

      JsName name = ((JsNameRef) q).getName();
      if (name == null) {
        return;
      }

      // caughtFunction is the JsFunction translated from Exceptions.wrap
      if (name.getStaticRef() != wrapFunction) {
        return;
      }

      // $stackDepth = stackIndex
      SourceInfo info = x.getSourceInfo();
      JsBinaryOperation reset = new JsBinaryOperation(info,
          JsBinaryOperator.ASG, stackDepth.makeRef(info),
          eeVisitor.stackIndexRef(info));

      ctx.insertAfter(reset.makeStmt());
    }
  }

  /**
   * The EntryExitVisitor handles pushing and popping frames onto the emulated
   * stack. It will operate on exactly one JsFunction. The basic transformation
   * is to add a push operation at every function entry, and then a pop
   * operation for every statement that might be the final statement executed by
   * the function.
   * <p>
   * General stack depth entry/exit code:
   * 
   * <pre>
   * function foo() {
   *   var stackIndex;
   *   $stack[stackIndex = ++$stackDepth] = foo;
   *   
   *   ... do stuff ..
   *   
   *   $stackDepth = stackIndex - 1;
   * }
   * </pre>
   * <p>
   * For more complicated control flows involving return statements in try
   * blocks with as associated finally block, it is necessary to introduce a
   * local variable to indicate if control flow is expected to terminate
   * normally at the end of the finally block:
   * 
   * <pre>
   * var exitingEarly;
   * try {
   *   if (...) {
   *     return (exitingEarly = true, new Foo());
   *   }
   *   ...
   * } finally {
   *   ... existing finally code ..
   *   exitingEarly && $stackDepth = stackIndex - 1;
   * }
   * </pre>
   * A separate local variable is used for each try/finally nested within a
   * finally block.
   * <p>
   * Try statements without a catch block will have a catch block added to them
   * so that catch blocks are the only places where flow-control may jump to.
   * All catch blocks are altered so that the global $stackDepth variable is
   * reset to the local stack index value. This allows browser-native exceptions
   * to be created with the correct stack trace before the finally code is
   * executed with a correct stack depth.
   * 
   * <pre>
   * try {
   *   foo();
   * } finally {
   *   bar();
   * }
   * </pre>
   * 
   * becomes
   * 
   * <pre>
   * try {
   *   foo();
   * } catch (e) {
   *   e = wrap(e);
   *   $stackDepth = stackIndex;
   *   throw e;
   * } finally {
   *   bar();
   * }
   * <p>
   * Note that there is no specific handling for explicit throw statements, as
   * the stack instrumentation must also handle browser-generated exceptions
   * (e.g. <code>null.a()</code>).
   */
  private class EntryExitVisitor extends JsModVisitor {

    /**
     * The name of a function-local variable to hold the invocation's slot in
     * the stack.
     */
    protected JsName stackIndex;

    private final JsFunction currentFunction;

    /**
     * Maps finally blocks to the local variable name which is used to indicate
     * if that finally block will exit the function early. This is a map and not
     * a single value because a finally block might be nested in another exit
     * block.
     */
    private Map<JsBlock, JsName> finallyBlocksToExitVariables = Maps.create();

    /**
     * This variable will indicate the finally block that contains the last
     * statement that will be executed if an unconditional flow control change
     * were to occur within the associated try block.
     */
    private JsBlock outerFinallyBlock;

    /**
     * Used if a return statement's expression could potentially trigger an
     * exception.
     */
    private JsName returnTemp;

    /**
     * Final cleanup for any new local variables that need to be created.
     */
    private List<JsVar> varsToAdd = Lists.create();

    public EntryExitVisitor(JsFunction currentFunction) {
      this.currentFunction = currentFunction;
    }

    /**
     * If the visitor is exiting the current function's block, add additional
     * local variables and the final stack-pop instructions.
     */
    @Override
    public void endVisit(JsBlock x, JsContext ctx) {
      if (x == currentFunction.getBody()) {

        // Add the entry code
        List<JsStatement> statements = x.getStatements();
        int idx = statements.isEmpty()
            || !(statements.get(0) instanceof JsVars) ? 0 : 1;

        // Add push and pop statements
        statements.add(idx, push(currentFunction));
        addPopAtEndOfBlock(x, false);

        // Add any needed variables
        JsVars vars;
        if (statements.get(0) instanceof JsVars) {
          vars = (JsVars) statements.get(0);
        } else {
          vars = new JsVars(currentFunction.getSourceInfo());
          statements.add(0, vars);
        }
        for (JsVar var : varsToAdd) {
          vars.add(var);
        }
      }
    }

    @Override
    public void endVisit(JsReturn x, JsContext ctx) {
      if (outerFinallyBlock != null) {
        // There is a finally block, so we need to set the early-exit flag
        JsBinaryOperation asg = new JsBinaryOperation(x.getSourceInfo(),
            JsBinaryOperator.ASG, earlyExitRef(outerFinallyBlock),
            JsBooleanLiteral.get(true));
        if (x.getExpr() == null) {
          if (ctx.canInsert()) {
            // exitingEarly = true; return;
            ctx.insertBefore(asg.makeStmt());
          } else {
            // {exitingEarly = true; return;}
            JsBlock block = new JsBlock(x.getSourceInfo());
            block.getStatements().add(asg.makeStmt());
            block.getStatements().add(x);
            ctx.replaceMe(block);
          }
        } else {
          // return (exitingEarly = true, expr);
          JsBinaryOperation op = new JsBinaryOperation(x.getSourceInfo(),
              JsBinaryOperator.COMMA, asg, x.getExpr());
          x.setExpr(op);
        }
      } else {
        if (x.getExpr() != null && x.getExpr().hasSideEffects()) {
          // temp = expr; pop(); return temp;
          SourceInfo info = x.getSourceInfo();
          JsBinaryOperation asg = new JsBinaryOperation(info,
              JsBinaryOperator.ASG, returnTempRef(info), x.getExpr());
          x.setExpr(returnTempRef(info));
          pop(x, asg, ctx);
        } else {
          // Otherwise, pop the stack frame
          pop(x, null, ctx);
        }
      }
    }

    /**
     * We want to look at unaltered versions of the catch block, so this is a
     * <code>visit<code> and not a <code>endVisit</code>.
     */
    @Override
    public boolean visit(JsCatch x, JsContext ctx) {
      // Reset the stack depth to the local index
      new CatchStackReset(this).accept(x);
      return true;
    }

    @Override
    public boolean visit(JsFunction x, JsContext ctx) {
      // Will be taken care of by the Bootstrap visitor
      return false;
    }

    @Override
    public boolean visit(JsTry x, JsContext ctx) {

      /*
       * Only the outermost finally block needs special treatment; try/finally
       * block within try blocks do not receive special treatment.
       */
      JsBlock finallyBlock = x.getFinallyBlock();
      if (finallyBlock != null && outerFinallyBlock == null) {
        outerFinallyBlock = finallyBlock;

        // Manual traversal
        accept(x.getTryBlock());

        if (x.getCatches().isEmpty()) {
          JsCatch c = makeSyntheticCatchBlock(x);
          x.getCatches().add(c);
        }
        assert x.getCatches().size() >= 1;
        acceptList(x.getCatches());

        // Exceptions in the finally block just exit the function
        assert outerFinallyBlock == finallyBlock;
        outerFinallyBlock = null;
        accept(finallyBlock);

        // Stack-pop instruction
        addPopAtEndOfBlock(finallyBlock, true);

        // Clean up entry after adding pop instruction
        finallyBlocksToExitVariables = Maps.remove(
            finallyBlocksToExitVariables, finallyBlock);
        return false;
      }

      // Normal visit
      return true;
    }

    /**
     * Create a reference to the function-local stack index variable, possibly
     * allocating it.
     */
    protected JsNameRef stackIndexRef(SourceInfo info) {
      if (stackIndex == null) {
        stackIndex = currentFunction.getScope().declareName(
            "JsStackEmulator_stackIndex", "stackIndex");

        JsVar var = new JsVar(info, stackIndex);
        varsToAdd = Lists.add(varsToAdd, var);
      }
      return stackIndex.makeRef(info);
    }

    /**
     * Code-gen function for generating the stack-pop statement at the end of a
     * block. A no-op if the last statement is a <code>throw</code> or
     * <code>return</code> statement, since it will have already caused a pop
     * statement to have been added.
     * 
     * @param checkEarlyExit if <code>true</code>, generates
     *          <code>earlyExit && pop()</code>
     */
    private void addPopAtEndOfBlock(JsBlock x, boolean checkEarlyExit) {
      JsStatement last = x.getStatements().isEmpty() ? null
          : x.getStatements().get(x.getStatements().size() - 1);
      if (last instanceof JsReturn || last instanceof JsThrow) {
        /*
         * Don't need a pop after a throw or break statement. This is an
         * optimization for the common case of returning a value as the last
         * statement, but doesn't cover all flow-control cases.
         */
        return;
      } else if (checkEarlyExit && !finallyBlocksToExitVariables.containsKey(x)) {
        /*
         * No early-exit variable was ever allocated for this block. This means
         * that the variable can never be true, and thus the stack-popping
         * expression will never be executed.
         */
        return;
      }

      // pop()
      SourceInfo info = x.getSourceInfo();
      JsExpression op = pop(info);

      if (checkEarlyExit) {
        // earlyExit && pop()
        op = new JsBinaryOperation(info, JsBinaryOperator.AND, earlyExitRef(x),
            op);
      }

      x.getStatements().add(op.makeStmt());
    }

    /**
     * Generate a name reference to the early-exit variable for a given block,
     * possibly allocating a new variable.
     */
    private JsNameRef earlyExitRef(JsBlock x) {
      JsName earlyExitName = finallyBlocksToExitVariables.get(x);
      if (earlyExitName == null) {
        earlyExitName = currentFunction.getScope().declareName(
            "JsStackEmulator_exitingEarly"
                + finallyBlocksToExitVariables.size(), "exitingEarly");

        finallyBlocksToExitVariables = Maps.put(finallyBlocksToExitVariables,
            x, earlyExitName);
        JsVar var = new JsVar(x.getSourceInfo(), earlyExitName);
        varsToAdd = Lists.add(varsToAdd, var);
      }
      return earlyExitName.makeRef(x.getSourceInfo());
    }

    private JsCatch makeSyntheticCatchBlock(JsTry x) {
      /*
       * catch (e) { e = wrap(e); throw e; }
       */
      SourceInfo info = x.getSourceInfo();

      JsCatch c = new JsCatch(info, currentFunction.getScope(), "e");
      JsName paramName = c.getParameter().getName();

      // wrap(e)
      JsInvocation wrapCall = new JsInvocation(info);
      wrapCall.setQualifier(wrapFunction.getName().makeRef(info));
      wrapCall.getArguments().add(paramName.makeRef(info));

      // e = wrap(e)
      JsBinaryOperation asg = new JsBinaryOperation(info, JsBinaryOperator.ASG,
          paramName.makeRef(info), wrapCall);

      // throw e
      JsThrow throwStatement = new JsThrow(info, paramName.makeRef(info));

      JsBlock body = new JsBlock(info);
      body.getStatements().add(asg.makeStmt());
      body.getStatements().add(throwStatement);
      c.setBody(body);
      return c;
    }

    /**
     * Pops the stack frame.
     * 
     * @param x the statement that will cause the pop
     * @param ctx the visitor context
     */
    private void pop(JsStatement x, JsExpression expr, JsContext ctx) {
      // $stackDepth = stackIndex - 1
      SourceInfo info = x.getSourceInfo();

      JsExpression op = pop(info);

      if (ctx.canInsert()) {
        if (expr != null) {
          ctx.insertBefore(expr.makeStmt());
        }
        ctx.insertBefore(op.makeStmt());
      } else {
        JsBlock block = new JsBlock(info);
        if (expr != null) {
          block.getStatements().add(expr.makeStmt());
        }
        block.getStatements().add(op.makeStmt());
        block.getStatements().add(x);
        ctx.replaceMe(block);
      }
    }

    /**
     * Decrement the $stackDepth variable.
     */
    private JsExpression pop(SourceInfo info) {
      JsBinaryOperation sub = new JsBinaryOperation(info, JsBinaryOperator.SUB,
          stackIndexRef(info), new JsNumberLiteral(info, 1));
      JsBinaryOperation op = new JsBinaryOperation(info, JsBinaryOperator.ASG,
          stackDepth.makeRef(info), sub);
      return op;
    }

    /**
     * Create the function-entry code.
     */
    private JsStatement push(HasSourceInfo x) {
      SourceInfo info = x.getSourceInfo();

      JsNameRef stackRef = stack.makeRef(info);
      JsNameRef stackDepthRef = stackDepth.makeRef(info);
      JsExpression currentFunctionRef;
      if (currentFunction.getName() == null) {
        // Anonymous
        currentFunctionRef = JsNullLiteral.INSTANCE;
      } else {
        currentFunctionRef = currentFunction.getName().makeRef(info);
      }

      // ++stackDepth
      JsUnaryOperation inc = new JsPrefixOperation(info, JsUnaryOperator.INC,
          stackDepthRef);

      // stackIndex = ++stackDepth
      JsBinaryOperation stackIndexOp = new JsBinaryOperation(info,
          JsBinaryOperator.ASG, stackIndexRef(info), inc);

      // stack[stackIndex = ++stackDepth]
      JsArrayAccess access = new JsArrayAccess(info, stackRef, stackIndexOp);

      // stack[stackIndex = ++stackDepth] = currentFunction
      JsBinaryOperation op = new JsBinaryOperation(info, JsBinaryOperator.ASG,
          access, currentFunctionRef);

      return op.makeStmt();
    }

    private JsNameRef returnTempRef(SourceInfo info) {
      if (returnTemp == null) {
        returnTemp = currentFunction.getScope().declareName(
            "JsStackEmulator_returnTemp", "returnTemp");

        JsVar var = new JsVar(info, returnTemp);
        varsToAdd = Lists.add(varsToAdd, var);
      }
      return returnTemp.makeRef(info);
    }
  }

  /**
   * Creates a visitor to instrument each JsFunction in the jsProgram.
   */
  private class InstrumentAllFunctions extends JsVisitor {

    @Override
    public void endVisit(JsFunction x, JsContext ctx) {
      if (!x.getBody().getStatements().isEmpty()) {
        JsName fnName = x.getName();
        JMethod method = jjsmap.nameToMethod(fnName);
        /**
         * Do not instrumental immortal types because they are potentially
         * evaluated before anything else has been defined.
         */
        if (method != null && jprogram.immortalCodeGenTypes.contains(method.getEnclosingType())) {
          return;
        }
        if (recordLineNumbers) {
          (new LocationVisitor(x)).accept(x.getBody());
        } else {
          (new EntryExitVisitor(x)).accept(x.getBody());
        }
      }
    }
  }

  /**
   * Extends EntryExit visitor to record location information in the AST. This
   * visitor will modify every JsExpression that can potentially result in a
   * change of flow control with file and line number data.
   * <p>
   * This simply generates code to set entries in the <code>$location</code>
   * stack, parallel to <code>$stack</code>:
   * 
   * <pre>
   * ($location[stackIndex] = 'Foo.java:' + 42, expr);
   * </pre>
   * 
   * Inclusion of file names is dependent on the value of the
   * {@link JsStackEmulator#recordFileNames} field.
   */
  private class LocationVisitor extends EntryExitVisitor {
    private String lastFile;
    private int lastLine;

    /**
     * Nodes in this set are used in a context that expects a reference, not
     * just an arbitrary expression. For example, <code>delete</code> takes a
     * reference. These are tracked because it wouldn't be safe to rewrite
     * <code>delete foo.bar</code> to <code>delete (line='123',foo).bar</code>.
     */
    private final Set<JsNode> nodesInRefContext = new HashSet<JsNode>();

    public LocationVisitor(JsFunction function) {
      super(function);
      resetPosition();
    }

    @Override
    public void endVisit(JsArrayAccess x, JsContext ctx) {
      record(x, ctx);
    }

    @Override
    public void endVisit(JsBinaryOperation x, JsContext ctx) {
      if (x.getOperator().isAssignment()) {
        record(x, ctx);
      }
    }

    @Override
    public void endVisit(JsInvocation x, JsContext ctx) {
      nodesInRefContext.remove(x.getQualifier());
      record(x, ctx);
    }

    @Override
    public void endVisit(JsNameRef x, JsContext ctx) {
      record(x, ctx);
    }

    @Override
    public void endVisit(JsNew x, JsContext ctx) {
      record(x, ctx);
    }

    @Override
    public void endVisit(JsPostfixOperation x, JsContext ctx) {
      record(x, ctx);
    }

    @Override
    public void endVisit(JsPrefixOperation x, JsContext ctx) {
      record(x, ctx);
      nodesInRefContext.remove(x.getArg());
    }

    /**
     * This is essentially a hacked-up version of JsFor.traverse to account for
     * flow control differing from visitation order. It resets lastFile and
     * lastLine before the condition and increment expressions in the for loop
     * so that location data will be recorded correctly.
     */
    @Override
    public boolean visit(JsFor x, JsContext ctx) {
      if (x.getInitExpr() != null) {
        x.setInitExpr(accept(x.getInitExpr()));
      } else if (x.getInitVars() != null) {
        x.setInitVars(accept(x.getInitVars()));
      }

      if (x.getCondition() != null) {
        resetPosition();
        x.setCondition(accept(x.getCondition()));
      }

      if (x.getIncrExpr() != null) {
        resetPosition();
        x.setIncrExpr(accept(x.getIncrExpr()));
      }

      accept(x.getBody());
      return false;
    }

    @Override
    public boolean visit(JsInvocation x, JsContext ctx) {
      nodesInRefContext.add(x.getQualifier());
      return true;
    }

    @Override
    public boolean visit(JsPrefixOperation x, JsContext ctx) {
      if (x.getOperator() == JsUnaryOperator.DELETE
          || x.getOperator() == JsUnaryOperator.TYPEOF) {
        nodesInRefContext.add(x.getArg());
      }
      return true;
    }

    /**
     * Similar to JsFor, this resets the current location information before
     * evaluating the condition.
     */
    @Override
    public boolean visit(JsWhile x, JsContext ctx) {
      resetPosition();
      x.setCondition(accept(x.getCondition()));
      accept(x.getBody());
      return false;
    }

    /**
     * Strips off the final name segment.
     */
    private String baseName(String fileName) {
      // Try the system path separator
      int lastIndex = fileName.lastIndexOf(File.separator);
      if (lastIndex == -1) {
        // Otherwise, try URL path separator
        lastIndex = fileName.lastIndexOf('/');
      }
      if (lastIndex != -1) {
        return fileName.substring(lastIndex + 1);
      } else {
        return fileName;
      }
    }

    private void record(JsExpression x, JsContext ctx) {
      if (ctx.isLvalue()) {
        // Assignments to comma expressions aren't legal
        return;
      } else if (nodesInRefContext.contains(x)) {
        // Don't modify references into non-references
        return;
      } else if (x.getSourceInfo().getStartLine() == lastLine
          && (!recordFileNames || x.getSourceInfo().getFileName().equals(
              lastFile))) {
        // Same location; ignore
        return;
      }

      SourceInfo info = x.getSourceInfo();

      // ($locations[stackIndex] = fileName + lineNumber, x)
      JsExpression location = new JsStringLiteral(info,
          String.valueOf(lastLine = info.getStartLine()));
      if (recordFileNames) {
        // 'fileName:' + lineNumber
        JsStringLiteral stringLit = new JsStringLiteral(info,
            baseName(lastFile = info.getFileName()) + ":");
        location = new JsBinaryOperation(info, JsBinaryOperator.ADD, stringLit,
            location);
      }

      JsArrayAccess access = new JsArrayAccess(info, lineNumbers.makeRef(info),
          stackIndexRef(info));
      JsBinaryOperation asg = new JsBinaryOperation(info, JsBinaryOperator.ASG,
          access, location);

      JsBinaryOperation comma = new JsBinaryOperation(info,
          JsBinaryOperator.COMMA, asg, x);

      ctx.replaceMe(comma);
    }

    private void resetPosition() {
      lastFile = "";
      lastLine = -1;
    }
  }

  /**
   * The StackTraceCreator code refers to identifiers defined in JsRootScope,
   * which are unobfuscatable. This visitor replaces references to those symbols
   * with references to our locally-defined, obfuscatable names.
   */
  private class ReplaceUnobfuscatableNames extends JsModVisitor {
    // See JsRootScope for the definition of these names
    private final JsName rootLineNumbers = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$location");
    private final JsName rootStack = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$stack");
    private final JsName rootStackDepth = JsRootScope.INSTANCE.findExistingUnobfuscatableName("$stackDepth");

    @Override
    public void endVisit(JsNameRef x, JsContext ctx) {
      JsName name = x.getName();
      JsNameRef newRef = null;

      if (name == rootStack) {
        newRef = stack.makeRef(x.getSourceInfo());
      } else if (name == rootStackDepth) {
        newRef = stackDepth.makeRef(x.getSourceInfo());
      } else if (name == rootLineNumbers) {
        newRef = lineNumbers.makeRef(x.getSourceInfo());
      }

      if (newRef == null) {
        return;
      }

      assert x.getQualifier() == null;
      ctx.replaceMe(newRef);
    }
  }

  /**
   * Corresponds to property compiler.stackMode in EmulateJsStack.gwt.xml
   * module.
   */
  public enum StackMode {
    STRIP, NATIVE, EMULATED;
  }

  public static void exec(JProgram jprogram, JsProgram jsProgram,
      PropertyOracle[] propertyOracles,
      JavaToJavaScriptMap jjsmap) {
    if (getStackMode(propertyOracles) == StackMode.EMULATED) {
      (new JsStackEmulator(jprogram, jsProgram, propertyOracles, jjsmap)).execImpl();
    }
  }

  public static StackMode getStackMode(PropertyOracle[] propertyOracles) {
    SelectionProperty property;
    try {
      property = propertyOracles[0].getSelectionProperty(TreeLogger.NULL,
          PROPERTY_NAME);
    } catch (BadPropertyValueException e) {
      // Should be inherited via Core.gwt.xml
      throw new InternalCompilerException("Expected property " + PROPERTY_NAME
          + " not defined", e);
    }

    String value = property.getCurrentValue();
    assert value != null : property.getName() + " did not have a value";
    StackMode stackMode = StackMode.valueOf(value.toUpperCase());
    // Check for multiply defined properties
    if (propertyOracles.length > 1) {
      for (int i = 1; i < propertyOracles.length; ++i) {
        try {
          property = propertyOracles[i].getSelectionProperty(TreeLogger.NULL,
              PROPERTY_NAME);
        } catch (BadPropertyValueException e) {
          // OK!
        }
        assert value.equals(property.getCurrentValue()) : "compiler.stackMode property has multiple values.";
      }
    }
    return stackMode;
  }

  private JsFunction wrapFunction;
  private JsName lineNumbers;
  private JProgram jprogram;
  private final JsProgram jsProgram;
  private JavaToJavaScriptMap jjsmap;
  private boolean recordFileNames;
  private boolean recordLineNumbers;
  private JsName stack;
  private JsName stackDepth;

  private JsStackEmulator(JProgram jprogram, JsProgram jsProgram,
      PropertyOracle[] propertyOracles,
      JavaToJavaScriptMap jjsmap) {
    this.jprogram = jprogram;
    this.jsProgram = jsProgram;
    this.jjsmap = jjsmap;

    assert propertyOracles.length > 0;
    PropertyOracle oracle = propertyOracles[0];
    try {
      List<String> values = oracle.getConfigurationProperty(
          "compiler.emulatedStack.recordFileNames").getValues();
      recordFileNames = Boolean.valueOf(values.get(0));

      values = oracle.getConfigurationProperty(
          "compiler.emulatedStack.recordLineNumbers").getValues();
      recordLineNumbers = recordFileNames || Boolean.valueOf(values.get(0));
    } catch (BadPropertyValueException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void execImpl() {
    wrapFunction = jsProgram.getIndexedFunction("Exceptions.wrap");
    if (wrapFunction == null) {
      // No exceptions caught? Weird, but possible.
      return;
    }
    initNames();
    makeVars();
    (new ReplaceUnobfuscatableNames()).accept(jsProgram);
    (new InstrumentAllFunctions()).accept(jsProgram);
  }

  private void initNames() {
    stack = jsProgram.getScope().declareName("$JsStackEmulator_stack", "$stack");
    stackDepth = jsProgram.getScope().declareName("$JsStackEmulator_stackDepth",
        "$stackDepth");
    lineNumbers = jsProgram.getScope().declareName("$JsStackEmulator_location",
        "$location");
  }

  private void makeVars() {
    SourceInfo info = jsProgram.createSourceInfoSynthetic(getClass());
    JsVar stackVar = new JsVar(info, stack);
    stackVar.setInitExpr(new JsArrayLiteral(info));
    JsVar stackDepthVar = new JsVar(info, stackDepth);
    stackDepthVar.setInitExpr(new JsNumberLiteral(info, (-1)));
    JsVar lineNumbersVar = new JsVar(info, lineNumbers);
    lineNumbersVar.setInitExpr(new JsArrayLiteral(info));

    JsVars vars;
    JsStatement first = jsProgram.getGlobalBlock().getStatements().get(0);
    if (first instanceof JsVars) {
      vars = (JsVars) first;
    } else {
      vars = new JsVars(info);
      jsProgram.getGlobalBlock().getStatements().add(0, vars);
    }
    vars.add(stackVar);
    vars.add(stackDepthVar);
    vars.add(lineNumbersVar);
  }
}
