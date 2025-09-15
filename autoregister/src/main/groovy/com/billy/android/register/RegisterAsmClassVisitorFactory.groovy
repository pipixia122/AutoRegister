package com.billy.android.register

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * AGP 8.x 兼容的 AsmClassVisitorFactory 实现
 * 用于类扫描和代码注入
 */
abstract class RegisterAsmClassVisitorFactory implements AsmClassVisitorFactory<RegisterAsmClassVisitorFactory.Parameters> {

    interface Parameters extends InstrumentationParameters {
        @Input
        Property<Boolean> getEnabled()
        
        @InputFiles
        @Optional
        @PathSensitive(PathSensitivity.RELATIVE)
        Property<FileCollection> getConfigFiles()
        
        @Input
        ListProperty<String> getRegisterInfos()
    }

    /**
     * 创建ClassWriter，处理可能的异常
     */
    ClassWriter createClassWriter(int flags) {
        return new ClassWriter(flags) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2)
                } catch (Exception e) {
                    // 处理可能的异常
                    return "java/lang/Object"
                }
            }
        }
    }

    @Override
    ClassVisitor createClassVisitor(ClassContext classContext, 
                                   ClassVisitor nextClassVisitor) {
        // 从参数中获取注册信息
        List<String> registerInfoStrings = getParameters().getRegisterInfos().get()
        List<RegisterInfo> registerInfos = new ArrayList<>()
        
        // 解析注册信息
        for (String infoStr : registerInfoStrings) {
            RegisterInfo info = new RegisterInfo()
            info.init(infoStr)
            registerInfos.add(info)
        }
        
        // 创建代码插入处理器
        CodeInsertProcessor insertProcessor = new CodeInsertProcessor()
        
        // 返回注册类访问器
        return new RegisterClassVisitor(classContext, nextClassVisitor, registerInfos, insertProcessor)
    }

    @Override
    boolean isInstrumentable(ClassData classData) {
        // 获取所有注册信息
        List<String> registerInfoStrings = getParameters().getRegisterInfos().get()
        
        for (String infoStr : registerInfoStrings) {
            RegisterInfo info = new RegisterInfo()
            info.init(infoStr)
            
            // 使用临时创建的CodeScanProcessor检查是否需要扫描
            CodeScanProcessor scanProcessor = new CodeScanProcessor()
            if (info.hasInitClass || scanProcessor.shouldScanClass(classData.className, info)) {
                return true
            }
        }
        
        return false
    }

    /**
     * 注册类访问器 - 静态内部类以避免序列化问题
     * 负责识别需要处理的类并应用相应的变换
     */
    static class RegisterClassVisitor extends ClassVisitor {
        private final ClassContext classContext
        private final List<RegisterInfo> registerInfos
        private final CodeInsertProcessor insertProcessor
        private String className

        RegisterClassVisitor(ClassContext classContext, ClassVisitor cv, 
                           List<RegisterInfo> registerInfos, CodeInsertProcessor insertProcessor) {
            super(Opcodes.ASM9, cv)
            this.classContext = classContext
            this.registerInfos = registerInfos
            this.insertProcessor = insertProcessor
        }

        @Override
        void visit(int version, int access, String name, String signature, 
                   String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces)
            this.className = name
        }

        @Override
        MethodVisitor visitMethod(int access, String name, String descriptor, 
                                 String signature, String[] exceptions) {
            // 获取原始方法访问器
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            
            // 检查是否需要进行代码插入
            for (RegisterInfo info : registerInfos) {
                // 如果是初始化类并且匹配方法名和描述符
                if (info.hasInitClass && className.replace('/', '.').equals(info.initClassName) &&
                        name.equals(info.methodName) && descriptor.equals(info.methodDesc)) {
                    // 返回方法访问器进行代码插入
                    return new RegisterMethodVisitor(mv, access, name, descriptor, info)
                }
            }
            
            return mv
        }
    }

    /**
     * 注册方法访问器 - 静态内部类以避免序列化问题
     * 负责在指定方法中插入注册代码
     */
    static class RegisterMethodVisitor extends MethodVisitor {
        private final RegisterInfo info

        RegisterMethodVisitor(MethodVisitor mv, int access, String name, String desc, RegisterInfo info) {
            super(Opcodes.ASM9, mv)
            this.info = info
        }

        @Override
        void visitInsn(int opcode) {
            // 在返回指令前插入代码
            if (opcode == Opcodes.RETURN) {
                injectRegisterCode()
            }
            super.visitInsn(opcode)
        }

        /**
         * 注入注册代码
         */
        private void injectRegisterCode() {
            // 为每个需要注册的类生成注册代码
            for (String clazzName : info.classList) {
                try {
                    // 创建类实例
                    mv.visitTypeInsn(Opcodes.NEW, clazzName.replace('.', '/'))
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, clazzName.replace('.', '/'), "<init>", "()V", false)
                    
                    // 调用注册方法
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, info.initClassName.replace('.', '/'),
                            info.registerMethodName, info.registerMethodDesc, false)
                } catch (Exception e) {
                    e.printStackTrace()
                }
            }
        }
    }
}