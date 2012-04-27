/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.path;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.NullableFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrThrowStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrBuiltinTypeClassExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrIndexProperty;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrGdkMethod;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrTupleType;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.TypeInferenceHelper;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.GrExpressionImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;

import static com.intellij.psi.util.PsiUtil.substituteTypeParameter;

/**
 * @author ilyas
 */
public class GrIndexPropertyImpl extends GrExpressionImpl implements GrIndexProperty {
  private static final Logger LOG = Logger.getInstance(GrIndexPropertyImpl.class);

  private static final Function<GrIndexPropertyImpl, PsiType> TYPE_CALCULATOR = new NullableFunction<GrIndexPropertyImpl, PsiType>() {
    @Override
    public PsiType fun(GrIndexPropertyImpl index) {
      GrExpression selected = index.getInvokedExpression();
      PsiType thisType = selected.getType();

      if (thisType == null) return null;

      GrArgumentList argList = index.getArgumentList();
      if (argList == null) return null;

      PsiType[] argTypes = PsiUtil.getArgumentTypes(argList);
      if (argTypes == null) return null;

      final PsiManager manager = index.getManager();
      final GlobalSearchScope resolveScope = index.getResolveScope();

      if (argTypes.length == 0) {
        PsiType arrType = null;
        if (selected instanceof GrBuiltinTypeClassExpression) {
          arrType = ((GrBuiltinTypeClassExpression)selected).getPrimitiveType();
        }

        if (selected instanceof GrReferenceExpression) {
          final PsiElement resolved = ((GrReferenceExpression)selected).resolve();
          if (resolved instanceof PsiClass) {
            String qname = ((PsiClass)resolved).getQualifiedName();
            if (qname != null) {
              arrType = TypesUtil.createTypeByFQClassName(qname, index);
            }
          }
        }

        if (arrType != null) {
          final PsiArrayType param = arrType.createArrayType();
          return TypesUtil.createJavaLangClassType(param, index.getProject(), resolveScope);
        }
      }

      if (PsiImplUtil.isSimpleArrayAccess(thisType, argTypes, manager, resolveScope, PsiUtil.isLValue(index))) {
        return TypesUtil.boxPrimitiveType(((PsiArrayType)thisType).getComponentType(), manager, resolveScope);
      }

      final GroovyResolveResult[] candidates = index.multiResolve(false);
      PsiType overloadedOperatorType = ResolveUtil.extractReturnTypeFromCandidate(PsiImplUtil.extractUniqueResult(candidates), index);

      PsiType componentType = extractMapValueType(thisType, argTypes, manager, resolveScope);

      if (overloadedOperatorType != null &&
          (componentType == null || !TypesUtil.isAssignable(overloadedOperatorType, componentType, manager, resolveScope))) {
        return TypesUtil.boxPrimitiveType(overloadedOperatorType, manager, resolveScope);
      }
      return componentType;
    }

    @Nullable
    private PsiType extractMapValueType(PsiType thisType, PsiType[] argTypes, PsiManager manager, GlobalSearchScope resolveScope) {
      if (argTypes.length != 1 || !InheritanceUtil.isInheritor(thisType, CommonClassNames.JAVA_UTIL_MAP)) return null;
      final PsiType substituted = substituteTypeParameter(thisType, CommonClassNames.JAVA_UTIL_MAP, 1, true);
      return TypesUtil.boxPrimitiveType(substituted, manager, resolveScope);
    }
  };
  private static final ResolveCache.PolyVariantResolver<MyReference> RESOLVER =
    new ResolveCache.PolyVariantResolver<MyReference>() {
      @Override
      public GroovyResolveResult[] resolve(MyReference index, boolean incompleteCode) {
        return index.getElement().resolveImpl(incompleteCode, null);
      }
    };

  private MyReference myReference = new MyReference();

  private GroovyResolveResult[] resolveImpl(boolean incompleteCode, @Nullable GrExpression upToArgument) {
    GrExpression invoked = getInvokedExpression();
    PsiType thisType = invoked.getType();

    if (thisType == null) return GroovyResolveResult.EMPTY_ARRAY;

    GrArgumentList argList = getArgumentList();
    if (argList == null) return GroovyResolveResult.EMPTY_ARRAY;

    PsiType[] argTypes = PsiUtil
      .getArgumentTypes(argList.getNamedArguments(), argList.getExpressionArguments(), GrClosableBlock.EMPTY_ARRAY, true, upToArgument);
    if (argTypes == null) return GroovyResolveResult.EMPTY_ARRAY;

    final PsiManager manager = getManager();
    final GlobalSearchScope resolveScope = getResolveScope();

    if (argTypes.length == 0) {
      PsiType arrType = null;
      if (invoked instanceof GrBuiltinTypeClassExpression) {
        arrType = ((GrBuiltinTypeClassExpression)invoked).getPrimitiveType();
      }

      if (invoked instanceof GrReferenceExpression) {
        final PsiElement resolved = ((GrReferenceExpression)invoked).resolve();
        if (resolved instanceof PsiClass) {
          String qname = ((PsiClass)resolved).getQualifiedName();
          if (qname != null) {
            arrType = TypesUtil.createTypeByFQClassName(qname, this);
          }
        }
      }

      if (arrType != null) {
        return GroovyResolveResult.EMPTY_ARRAY;
      }
    }

    if (PsiImplUtil.isSimpleArrayAccess(thisType, argTypes, manager, resolveScope, PsiUtil.isLValue(this))) {
      return GroovyResolveResult.EMPTY_ARRAY;
    }

    GroovyResolveResult[] candidates;
    final String name;
    if (PsiUtil.isLValue(this)) {
      name = "putAt";
      if (!incompleteCode) {
        argTypes = ArrayUtil.append(argTypes, TypeInferenceHelper.getInitializerFor(this), PsiType.class);
      }
    }
    else {
      name = "getAt";
    }
    candidates = ResolveUtil.getMethodCandidates(thisType, name, this, true, incompleteCode, false, argTypes);

    //hack for remove DefaultGroovyMethods.getAt(Object, ...)
    if (candidates.length == 2) {
      for (int i = 0; i < candidates.length; i++) {
        GroovyResolveResult candidate = candidates[i];
        final PsiElement element = candidate.getElement();
        if (element instanceof GrGdkMethod) {
          final PsiMethod staticMethod = ((GrGdkMethod)element).getStaticMethod();
          final PsiParameter param = staticMethod.getParameterList().getParameters()[0];
          if (param.getType().equalsToText(CommonClassNames.JAVA_LANG_OBJECT)) {
            return new GroovyResolveResult[]{candidates[1 - i]};
          }
        }
      }
    }

    if (candidates.length != 1) {
      final GrTupleType tupleType = new GrTupleType(argTypes, JavaPsiFacade.getInstance(getProject()), resolveScope);
      candidates = ResolveUtil.getMethodCandidates(thisType, name, this, tupleType);
    }
    return candidates;
  }

  public GrIndexPropertyImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(GroovyElementVisitor visitor) {
    visitor.visitIndexProperty(this);
  }

  public String toString() {
    return "Property by index";
  }

  @NotNull
  public GrExpression getInvokedExpression() {
    return findNotNullChildByClass(GrExpression.class);
  }

  @Nullable
  public GrArgumentList getArgumentList() {
    return findChildByClass(GrArgumentList.class);
  }

  @NotNull
  @Override
  public GroovyResolveResult[] multiResolve(boolean incompleteCode) {
    return (GroovyResolveResult[])ResolveCache.getInstance(getProject()).resolveWithCaching(myReference, RESOLVER, false, incompleteCode);
  }

  public PsiType getType() {
    return GroovyPsiManager.getInstance(getProject()).getType(this, TYPE_CALCULATOR);
  }

  @Override
  public PsiType getNominalType() {
    if (getParent() instanceof GrThrowStatement) return super.getNominalType();
    
    LOG.assertTrue(PsiUtil.isLValue(this), "it is assumed that nominal type is invoked only for assignment lhs");

    GroovyResolveResult[] candidates = multiResolve(true);
    if (candidates.length == 1) {
      return extractLastParameterType(candidates[0]);
    }
    return null;
  }

  @Nullable
  private PsiType extractLastParameterType(GroovyResolveResult candidate) {
    PsiElement element = candidate.getElement();
    if (element instanceof PsiMethod) {
      PsiParameter[] parameters = ((PsiMethod)element).getParameterList().getParameters();
      if (parameters.length > 1) {
        PsiParameter last = parameters[parameters.length - 1];
        return TypesUtil.substituteBoxAndNormalizeType(last.getType(), candidate.getSubstitutor(), this);
      }
    }
    return null;
  }

  @NotNull
  @Override
  public GrNamedArgument[] getNamedArguments() {
    GrArgumentList list = getArgumentList();
    return list == null ? GrNamedArgument.EMPTY_ARRAY : list.getNamedArguments();
  }

  @NotNull
  @Override
  public GrExpression[] getExpressionArguments() {
    GrArgumentList list = getArgumentList();
    return list == null ? GrExpression.EMPTY_ARRAY : list.getExpressionArguments();
  }

  @Override
  public GrNamedArgument addNamedArgument(GrNamedArgument namedArgument) throws IncorrectOperationException {
    GrArgumentList list = getArgumentList();
    if (list == null) throw new IncorrectOperationException("Argument list is null");
    return list.addNamedArgument(namedArgument);
  }

  @NotNull
  @Override
  public GroovyResolveResult[] getCallVariants(@Nullable GrExpression upToArgument) {
    if (upToArgument == null) {
      return multiResolve(true);
    }
    return resolveImpl(true, upToArgument);
  }

  @NotNull
  @Override
  public GrClosableBlock[] getClosureArguments() {
    return GrClosableBlock.EMPTY_ARRAY;
  }

  @Override
  public PsiMethod resolveMethod() {
    PsiElement resolved = PsiImplUtil.extractUniqueElement(multiResolve(false));
    if (resolved instanceof PsiMethod) {
      return (PsiMethod)resolved;
    }
    return null;
  }

  @NotNull
  @Override
  public GroovyResolveResult advancedResolve() {
    GroovyResolveResult[] results = multiResolve(false);
    return results.length == 1 ? results[0] : GroovyResolveResult.EMPTY_RESULT;
  }

  @Override
  public PsiReference getReference() {
    return myReference;
  }

  private class MyReference implements PsiPolyVariantReference {
    @Override
    public GrIndexPropertyImpl getElement() {
      return GrIndexPropertyImpl.this;
    }

    @Override
    public TextRange getRangeInElement() {
      final int offset = getArgumentList().getStartOffsetInParent();
      return new TextRange(offset, offset + 1);
    }

    @Override
    public PsiElement resolve() {
      return resolveMethod();
    }

    @NotNull
    @Override
    public String getCanonicalText() {
      return "Array-style access";
    }

    @Override
    public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
      return GrIndexPropertyImpl.this;
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
      return GrIndexPropertyImpl.this;
    }

    @Override
    public boolean isReferenceTo(PsiElement element) {
      return getManager().areElementsEquivalent(resolve(), element);
    }

    @NotNull
    @Override
    public Object[] getVariants() {
      return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }

    @Override
    public boolean isSoft() {
      return false;
    }

    @NotNull
    @Override
    public ResolveResult[] multiResolve(boolean incompleteCode) {
      return resolveImpl(incompleteCode, null);
    }
  }
}