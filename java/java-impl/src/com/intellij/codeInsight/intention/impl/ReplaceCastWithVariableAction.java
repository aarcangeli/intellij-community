// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.EquivalenceChecker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Danila Ponomarenko
 */
public class ReplaceCastWithVariableAction extends PsiElementBaseIntentionAction {
  private String myReplaceVariableName = "";

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    final PsiTypeCastExpression typeCastExpression = PsiTreeUtil.getParentOfType(element, PsiTypeCastExpression.class);
    final PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

    if (typeCastExpression == null || method == null) {
      return false;
    }

    final PsiExpression operand = PsiUtil.skipParenthesizedExprDown(typeCastExpression.getOperand());
    if (!(operand instanceof PsiReferenceExpression)) {
      return false;
    }

    final PsiReferenceExpression operandReference = (PsiReferenceExpression)operand;
    final PsiElement resolved = operandReference.resolve();
    if (!(resolved instanceof PsiParameter) && !(resolved instanceof PsiLocalVariable)) {
      return false;
    }

    final PsiLocalVariable replacement = findReplacement(method, (PsiVariable)resolved, typeCastExpression);
    if (replacement == null) {
      return false;
    }

    myReplaceVariableName = replacement.getName();
    setText(JavaBundle.message("intention.replace.cast.with.var.text", typeCastExpression.getText(), myReplaceVariableName));

    return true;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
    final PsiTypeCastExpression typeCastExpression = PsiTreeUtil.getParentOfType(element, PsiTypeCastExpression.class);

    if (typeCastExpression == null) {
      return;
    }

    final PsiElement toReplace = typeCastExpression.getParent() instanceof PsiParenthesizedExpression ? typeCastExpression.getParent() : typeCastExpression;
    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
    new CommentTracker().replaceAndRestoreComments(toReplace, factory.createExpressionFromText(myReplaceVariableName, toReplace));
  }

  @Nullable
  private static PsiLocalVariable findReplacement(@NotNull PsiMethod method,
                                                  @NotNull PsiVariable castedVar,
                                                  @NotNull PsiTypeCastExpression expression) {
    final TextRange expressionTextRange = expression.getTextRange();
    PsiExpression operand = PsiUtil.skipParenthesizedExprDown(expression.getOperand());
    List<PsiTypeCastExpression> found =
      SyntaxTraverser.psiTraverser(method)
                     .filter(PsiTypeCastExpression.class)
                     .filter(cast -> EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(cast.getOperand(), operand))
                     .toList();
    PsiResolveHelper resolveHelper = PsiResolveHelper.getInstance(method.getProject());
    for (PsiTypeCastExpression occurrence : found) {
      ProgressIndicatorProvider.checkCanceled();
      final TextRange occurrenceTextRange = occurrence.getTextRange();
      if (occurrence == expression || occurrenceTextRange.getEndOffset() >= expressionTextRange.getStartOffset()) {
        continue;
      }

      final PsiLocalVariable variable = getVariable(occurrence);

      final PsiCodeBlock methodBody = method.getBody();
      if (variable != null && methodBody != null &&
          resolveHelper.resolveReferencedVariable(variable.getName(), expression) == variable &&
          !isChangedBetween(castedVar, methodBody, occurrence, expression) &&
          !isChangedBetween(variable, methodBody, occurrence, expression)) {
        return variable;
      }
    }


    return null;
  }

  private static boolean isChangedBetween(@NotNull final PsiVariable variable,
                                          @NotNull final PsiElement scope,
                                          @NotNull final PsiElement start,
                                          @NotNull final PsiElement end) {
    if (variable.hasModifierProperty(PsiModifier.FINAL)) {
      return false;
    }

    final Ref<Boolean> result = new Ref<>();

    scope.accept(
      new JavaRecursiveElementWalkingVisitor() {
        private boolean inScope;

        @Override
        public void visitElement(@NotNull PsiElement element) {
          if (element == start) {
            inScope = true;
          }
          if (element == end) {
            inScope = false;
            stopWalking();
          }
          super.visitElement(element);
        }

        @Override
        public void visitAssignmentExpression(PsiAssignmentExpression expression) {
          if (inScope && expression.getLExpression() instanceof PsiReferenceExpression) {
            final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)expression.getLExpression();

            if (variable.equals(referenceExpression.resolve())) {
              result.set(true);
              stopWalking();
            }
          }
          super.visitAssignmentExpression(expression);
        }
      }
    );
    return result.get() == Boolean.TRUE;
  }

  @Nullable
  private static PsiLocalVariable getVariable(@NotNull PsiExpression occurrence) {
    final PsiElement parent = PsiUtil.skipParenthesizedExprUp(occurrence.getParent());

    if (parent instanceof PsiLocalVariable) {
      return (PsiLocalVariable)parent;
    }

    if (parent instanceof PsiAssignmentExpression) {
      final PsiAssignmentExpression assignmentExpression = (PsiAssignmentExpression)parent;
      if (assignmentExpression.getLExpression() instanceof PsiReferenceExpression) {
        final PsiReferenceExpression referenceExpression = (PsiReferenceExpression)assignmentExpression.getLExpression();
        final PsiElement resolved = referenceExpression.resolve();
        if (resolved instanceof PsiLocalVariable) {
          return (PsiLocalVariable)resolved;
        }
      }
    }

    return null;
  }

  @NotNull
  @Override
  public String getFamilyName() {
    return JavaBundle.message("intention.replace.cast.with.var.family");
  }
}
