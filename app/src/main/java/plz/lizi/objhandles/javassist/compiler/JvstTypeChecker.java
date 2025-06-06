/*
 * Javassist, a Java-bytecode translator toolkit.
 * Copyright (C) 1999- Shigeru Chiba. All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License.  Alternatively, the contents of this file may be used under
 * the terms of the GNU Lesser General Public License Version 2.1 or later,
 * or the Apache License Version 2.0.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 */

package plz.lizi.objhandles.javassist.compiler;

import plz.lizi.objhandles.javassist.ClassPool;
import plz.lizi.objhandles.javassist.CtClass;
import plz.lizi.objhandles.javassist.CtPrimitiveType;
import plz.lizi.objhandles.javassist.NotFoundException;
import plz.lizi.objhandles.javassist.compiler.ast.ASTList;
import plz.lizi.objhandles.javassist.compiler.ast.ASTree;
import plz.lizi.objhandles.javassist.compiler.ast.CallExpr;
import plz.lizi.objhandles.javassist.compiler.ast.CastExpr;
import plz.lizi.objhandles.javassist.compiler.ast.Expr;
import plz.lizi.objhandles.javassist.compiler.ast.Member;
import plz.lizi.objhandles.javassist.compiler.ast.Symbol;

/* Type checker accepting extended Java syntax for Javassist.
 */

public class JvstTypeChecker extends TypeChecker {
    private JvstCodeGen codeGen;

    public JvstTypeChecker(CtClass cc, ClassPool cp, JvstCodeGen gen) {
        super(cc, cp);
        codeGen = gen;
    }

    /* If the type of the expression compiled last is void,
     * add ACONST_NULL and change exprType, arrayDim, className.
     */
    public void addNullIfVoid() {
        if (exprType == VOID) {
            exprType = CLASS;
            arrayDim = 0;
            className = jvmJavaLangObject;
        }
    }

    /* To support $args, $sig, and $type.
     * $args is an array of parameter list.
     */
    @Override
    public void atMember(Member mem) throws CompileError {
        String name = mem.get();
        if (name.equals(codeGen.paramArrayName)) {
            exprType = CLASS;
            arrayDim = 1;
            className = jvmJavaLangObject;
        }
        else if (name.equals(JvstCodeGen.sigName)) {
            exprType = CLASS;
            arrayDim = 1;
            className = "java/lang/Class";
        }
        else if (name.equals(JvstCodeGen.dollarTypeName)
                 || name.equals(JvstCodeGen.clazzName)) {
            exprType = CLASS;
            arrayDim = 0;
            className = "java/lang/Class";
        }
        else
            super.atMember(mem);
    }

    @Override
    protected void atFieldAssign(Expr expr, int op, ASTree left, ASTree right)
        throws CompileError
    {
        if (left instanceof Member
            && ((Member)left).get().equals(codeGen.paramArrayName)) {
            right.accept(this);
            CtClass[] params = codeGen.paramTypeList;
            if (params == null)
                return;

            int n = params.length;
            for (int i = 0; i < n; ++i)
                compileUnwrapValue(params[i], expr.getLineNumber());
        }
        else
            super.atFieldAssign(expr, op, left, right);
    }

    @Override
    public void atCastExpr(CastExpr expr) throws CompileError {
        ASTList classname = expr.getClassName();
        if (classname != null && expr.getArrayDim() == 0) {
            ASTree p = classname.head();
            if (p instanceof Symbol && classname.tail() == null) {
                String typename = ((Symbol)p).get();
                if (typename.equals(codeGen.returnCastName)) {
                    atCastToRtype(expr);
                    return;
                }
                else if (typename.equals(JvstCodeGen.wrapperCastName)) {
                    atCastToWrapper(expr);
                    return;
                }
            }
        }

        super.atCastExpr(expr);
    }

    /**
     * Inserts a cast operator to the return type.
     * If the return type is void, this does nothing.
     */
    protected void atCastToRtype(CastExpr expr) throws CompileError {
        CtClass returnType = codeGen.returnType;
        expr.getOprand().accept(this);
        if (exprType == VOID || CodeGen.isRefType(exprType) || arrayDim > 0)
            compileUnwrapValue(returnType, expr.getLineNumber());
        else if (returnType instanceof CtPrimitiveType) {
            CtPrimitiveType pt = (CtPrimitiveType)returnType;
            int destType = MemberResolver.descToType(pt.getDescriptor(), expr.getLineNumber());
            exprType = destType;
            arrayDim = 0;
            className = null;
        }
    }

    protected void atCastToWrapper(CastExpr expr) throws CompileError {
        expr.getOprand().accept(this);
        if (CodeGen.isRefType(exprType) || arrayDim > 0)
            return;     // Object type.  do nothing.

        CtClass clazz = resolver.lookupClass(exprType, arrayDim, className, expr.getLineNumber());
        if (clazz instanceof CtPrimitiveType) {
            exprType = CLASS;
            arrayDim = 0;
            className = jvmJavaLangObject;
        }
    }

    /* Delegates to a ProcHandler object if the method call is
     * $proceed().  It may process $cflow().
     */
    @Override
    public void atCallExpr(CallExpr expr) throws CompileError {
        ASTree method = expr.oprand1();
        if (method instanceof Member) {
            String name = ((Member)method).get();
            if (codeGen.procHandler != null
                && name.equals(codeGen.proceedName)) {
                codeGen.procHandler.setReturnType(this,
                                                  (ASTList)expr.oprand2(), expr.getLineNumber());
                return;
            }
            else if (name.equals(JvstCodeGen.cflowName)) {
                atCflow((ASTList)expr.oprand2());
                return;
            }
        }

        super.atCallExpr(expr);
    }

    /* To support $cflow().
     */
    protected void atCflow(ASTList cname) throws CompileError {
        exprType = INT;
        arrayDim = 0;
        className = null;
    }

    /* To support $$.  ($$) is equivalent to ($1, ..., $n).
     * It can be used only as a parameter list of method call.
     */
    public boolean isParamListName(ASTList args) {
        if (codeGen.paramTypeList != null
            && args != null && args.tail() == null) {
            ASTree left = args.head();
            return (left instanceof Member
                    && ((Member)left).get().equals(codeGen.paramListName));
        }
        return false;
    }

    @Override
    public int getMethodArgsLength(ASTList args) {
        String pname = codeGen.paramListName;
        int n = 0;
        while (args != null) {
            ASTree a = args.head();
            if (a instanceof Member && ((Member)a).get().equals(pname)) {
                if (codeGen.paramTypeList != null)
                    n += codeGen.paramTypeList.length;
            }
            else
                ++n;

            args = args.tail();
        }

        return n;
    }

    @Override
    public void atMethodArgs(ASTList args, int[] types, int[] dims,
                                String[] cnames) throws CompileError {
        CtClass[] params = codeGen.paramTypeList;
        String pname = codeGen.paramListName;
        int i = 0;
        while (args != null) {
            ASTree a = args.head();
            if (a instanceof Member && ((Member)a).get().equals(pname)) {
                if (params != null) {
                    int n = params.length;
                    for (int k = 0; k < n; ++k) {
                        CtClass p = params[k];
                        setType(p, a.getLineNumber());
                        types[i] = exprType;
                        dims[i] = arrayDim;
                        cnames[i] = className;
                        ++i;
                    }
                }
            }
            else {
                a.accept(this);
                types[i] = exprType;
                dims[i] = arrayDim;
                cnames[i] = className;
                ++i;
            }

            args = args.tail();
        }
    }

    /* called by Javac#recordSpecialProceed().
     */
    void compileInvokeSpecial(ASTree target, String classname,
                              String methodname, String descriptor,
                              ASTList args)
        throws CompileError
    {
        target.accept(this);
        int nargs = getMethodArgsLength(args);
        atMethodArgs(args, new int[nargs], new int[nargs],
                     new String[nargs]);
        setReturnType(descriptor, target.getLineNumber());
        addNullIfVoid();
    }

    protected void compileUnwrapValue(CtClass type, int lineNumber) throws CompileError
    {
        if (type == CtClass.voidType)
            addNullIfVoid();
        else
            setType(type, lineNumber);
    }

    /* Sets exprType, arrayDim, and className;
     * If type is void, then this method does nothing.
     */
    public void setType(CtClass type, int lineNumber) throws CompileError {
        setType(type, 0, lineNumber);
    }

    private void setType(CtClass type, int dim, int lineNumber) throws CompileError {
        if (type.isPrimitive()) {
            CtPrimitiveType pt = (CtPrimitiveType)type;
            exprType = MemberResolver.descToType(pt.getDescriptor(), lineNumber);
            arrayDim = dim;
            className = null;
        }
        else if (type.isArray())
            try {
                setType(type.getComponentType(), dim + 1, lineNumber);
            }
            catch (NotFoundException e) {
                throw new CompileError("undefined type: " + type.getName(), lineNumber);
            }
        else {
            exprType = CLASS;
            arrayDim = dim;
            className = MemberResolver.javaToJvmName(type.getName());
        }
    }
}
