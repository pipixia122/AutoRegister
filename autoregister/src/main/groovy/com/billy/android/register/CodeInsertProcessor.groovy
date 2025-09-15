package com.billy.android.register

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * AGP 8.x 兼容的代码插入处理器
 * 负责在指定类的指定方法中插入注册代码
 * @author billy.qi
 * @since 17/3/20 11:48
 */
class CodeInsertProcessor {

    /**
     * 在指定的初始化类中插入注册代码
     * @param registerInfo 注册信息
     * @return 处理结果
     */
    byte[] insertInitCode(RegisterInfo registerInfo, byte[] classData) {
        println "[AutoRegister] Starting insertInitCode: initClass=${registerInfo.initClassName}, classesToRegister=${registerInfo.classList.size()}"
        
        if (!registerInfo.hasInitClass) {
            println "[AutoRegister] Skip insertInitCode: no init class found"
            return classData
        }
        
        if (registerInfo.classList.isEmpty()) {
            println "[AutoRegister] Skip insertInitCode: no classes to register"
            return classData
        }

        try {
            // 转换类名为内部名格式（使用斜杠分隔）
            String initClassName = registerInfo.initClassName.replace('.', '/')
            ClassReader reader = new ClassReader(classData)
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            
            println "[AutoRegister] Creating MyClassVisitor to process init class: ${initClassName}"
            // 创建类访问器，在访问方法时插入注册代码
            reader.accept(new MyClassVisitor(Opcodes.ASM9, writer, registerInfo), 0)
            println "[AutoRegister] Code insertion completed successfully"
            return writer.toByteArray()
        } catch (Exception e) {
            println "[AutoRegister] Error during code insertion: ${e.getMessage()}"
            e.printStackTrace()
            return classData
        }
    }

    /**
     * ASM类访问器，用于向指定方法中插入代码
     */
    class MyClassVisitor extends ClassVisitor {
        private RegisterInfo registerInfo
        private String className

        MyClassVisitor(int api, ClassVisitor cv, RegisterInfo registerInfo) {
            super(api, cv)
            this.registerInfo = registerInfo
            println "[AutoRegister] Created MyClassVisitor for ${registerInfo.initClassName}"
        }

        @Override
        void visit(int version, int access, String name, String signature,
                   String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces)
            this.className = name
            println "[AutoRegister] Visiting class: ${name} (init class: ${registerInfo.initClassName})"
        }

        @Override
        MethodVisitor visitMethod(int access, String name, String descriptor,
                                 String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            
            // 检查是否为需要注入代码的方法
            if (name.equals(registerInfo.methodName) && descriptor.equals(registerInfo.methodDesc)) {
                println "[AutoRegister] Found target method: ${name}${descriptor} in class ${className}"
                // 创建方法访问器用于代码生成
                return new MyMethodVisitor(mv, access, name, descriptor, registerInfo)
            }
            println "[AutoRegister] Skipping non-target method: ${name}${descriptor}"
            return mv
        }
    }

    /**
     * ASM方法访问器，用于在方法中插入代码
     */
    class MyMethodVisitor extends MethodVisitor {
        private RegisterInfo registerInfo

        MyMethodVisitor(MethodVisitor mv, int access, String name, String desc, RegisterInfo registerInfo) {
            super(Opcodes.ASM9, mv)
            this.registerInfo = registerInfo
            println "[AutoRegister] Created MyMethodVisitor for code injection"
        }

        @Override
        void visitInsn(int opcode) {
            // 在方法返回前插入注册代码
            if (opcode == Opcodes.RETURN) {
                println "[AutoRegister] Injecting registration code before RETURN instruction"
                insertCode(registerInfo)
            }
            super.visitInsn(opcode)
        }

        /**
         * 插入注册代码
         */
        private void insertCode(RegisterInfo registerInfo) {
            println "[AutoRegister] Starting code injection for ${registerInfo.classList.size()} classes"
            // 对于每个需要注册的类，生成注册代码
            for (String className : registerInfo.classList) {
                try {
                    println "[AutoRegister] Injecting registration for class: ${className}"
                    // 1. 创建类的实例
                    mv.visitTypeInsn(Opcodes.NEW, className.replace('.', '/'))
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, className.replace('.', '/'), "<init>", "()V", false)
                    
                    // 2. 调用注册方法
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, registerInfo.initClassName.replace('.', '/'),
                            registerInfo.registerMethodName, registerInfo.registerMethodDesc, false)
                    println "[AutoRegister] Successfully injected code for ${className}"
                } catch (Exception e) {
                    println "[AutoRegister] Error during injection for ${className}: ${e.getMessage()}"
                    e.printStackTrace()
                }
            }
            println "[AutoRegister] Code injection completed"
        }
    }
}