package com.billy.android.register

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.lang.reflect.Method
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
        @Optional
        Property<Boolean> getEnabled()
        
        @InputFiles
        @Optional
        @PathSensitive(PathSensitivity.RELATIVE)
        Property<FileCollection> getConfigFiles()
        
        @Input
        @Optional
        Property<String> getRegisterInfo()
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
        // 从参数中获取注册信息 - 兼容AGP 8.x的安全方式
        List<String> registerInfoStrings = new ArrayList<>()
        try {
            // 尝试标准方式
            Object parameters = getParameters()
            println "[AutoRegister] getParameters() in createClassVisitor returned: ${parameters?.getClass()?.getName() ?: 'NULL'}"
            if (parameters != null) {
                println "[AutoRegister] Parameters class: ${parameters.getClass().getName()}"
                println "[AutoRegister] Parameters toString: ${parameters.toString()}"
                // 检查是否有getRegisterInfo方法
                if (parameters.metaClass.respondsTo(parameters, 'getRegisterInfo')) {
                    println "[AutoRegister] Found getRegisterInfo method"
                    def registerInfoProperty = parameters.getRegisterInfo()
                    if (registerInfoProperty != null && registerInfoProperty.metaClass.respondsTo(registerInfoProperty, 'get')) {
                        registerInfoStrings.add(registerInfoProperty.get() as String)
                        println "[AutoRegister] Successfully got registerInfoStrings via getRegisterInfo.get(): count=${registerInfoStrings?.size()}"
                    }
                }
                // 如果上述方式失败，尝试直接访问属性
                else if (parameters.hasProperty('registerInfo')) {
                    println "[AutoRegister] Trying via property access"
                    def registerInfoProperty = parameters.getProperty('registerInfo')
                    if (registerInfoProperty != null && registerInfoProperty.metaClass.respondsTo(registerInfosProperty, 'get')) {
                        registerInfoStrings.add(registerInfosroperty.get() as String)
                        println "[AutoRegister] Successfully got registerInfoStrings via property.get(): count=${registerInfoStrings?.size()}"
                    }
                }
                // 如果仍然失败，尝试反射方式
                else {
                    try {
                        println "[AutoRegister] Trying via reflection"
                        Class<?> paramsClass = parameters.getClass()
                        Method getRegisterInfosMethod = paramsClass.getMethod('getRegisterInfo')
                        def registerInfosProperty = getRegisterInfosMethod.invoke(parameters)
                        if (registerInfosProperty != null) {
                            Method getMethod = registerInfosProperty.getClass().getMethod('get')
                            registerInfoStrings = getMethod.invoke(registerInfosProperty)
                            println "[AutoRegister] Successfully got registerInfoStrings via reflection: count=${registerInfoStrings?.size()}"
                        }
                    } catch (Exception e) {
                        println "[AutoRegister] Warning: Failed to get registerInfos via reflection: ${e.getMessage()}"
                    }
                }
            }
        } catch (Exception e) {
            println "[AutoRegister] Error getting registerInfos: ${e.getMessage()}"
        }
        
        // 确保registerInfoStrings不为null
        if (registerInfoStrings == null) {
            registerInfoStrings = new ArrayList<>()
        }
        
        println "[AutoRegister] Processing ${registerInfoStrings.size()} register info entries"
        
        List<RegisterInfo> registerInfos = new ArrayList<>()
        
        // 解析注册信息
        for (String infoStr : registerInfoStrings) {
            RegisterInfo info = new RegisterInfo()
            info.init(infoStr)
            registerInfos.add(info)
            println "[AutoRegister] Added register info: interface=${info.interfaceName ?: 'none'}, superClasses=${info.superClassNames}"
        }
        
        // 创建代码插入处理器
        CodeInsertProcessor insertProcessor = new CodeInsertProcessor()
        
        // 返回注册类访问器
        println "[AutoRegister] Creating RegisterClassVisitor with ${registerInfos.size()} register infos"
        return new RegisterClassVisitor(classContext, nextClassVisitor, registerInfos, insertProcessor)
    }

    @Override
    boolean isInstrumentable(ClassData classData) {
//        println "[AutoRegister] Checking if class is instrumentable: ${classData.className}"
        
        // 获取所有注册信息和启用状态 - 兼容AGP 8.x的安全方式
        List<String> registerInfoStrings = new ArrayList<>()
        boolean enabled = true
        
        try {
            // 尝试标准方式
            Object parameters = getParameters()
//            println "[AutoRegister] getParameters() in isInstrumentable returned: ${parameters?.getClass()?.getName() ?: 'NULL'}"
            if (parameters != null) {
//                println "[AutoRegister] Parameters class: ${parameters.getClass().getName()}"
//                println "[AutoRegister] Parameters toString: ${parameters.toString()}"
                // 检查是否启用
                try {
                    if (parameters.metaClass.respondsTo(parameters, 'getEnabled')) {
                        def enabledProperty = parameters.getEnabled()
                        if (enabledProperty != null && enabledProperty.metaClass.respondsTo(enabledProperty, 'get')) {
                            enabled = enabledProperty.get()
                        }
                    }
                } catch (Exception e) {
//                    println "[AutoRegister] Error getting enabled flag: ${e.getMessage()}"
                }
                
                // 即使禁用，也打印详细日志以帮助调试
                if (!enabled) {
//                    println "[AutoRegister] WARNING: Plugin is DISABLED! To enable, set enabled=true in your gradle config"
                    return false
                }
                
                // 检查是否有getRegisterInfos方法
                if (parameters.metaClass.respondsTo(parameters, 'getRegisterInfo')) {
                    def registerInfosProperty = parameters.getRegisterInfo()
                    if (registerInfosProperty != null && registerInfosProperty.metaClass.respondsTo(registerInfosProperty, 'get')) {
                        registerInfoStrings.add(registerInfosProperty.get() as String)
                    }
                }
                // 如果上述方式失败，尝试直接访问属性
                else if (parameters.hasProperty('registerInfo')) {
                    def registerInfosProperty = parameters.getProperty('registerInfo')
                    if (registerInfosProperty != null && registerInfosProperty.metaClass.respondsTo(registerInfosProperty, 'get')) {
                        registerInfoStrings.add(registerInfosProperty.get() as String)
                    }
                }
                // 如果仍然失败，尝试反射方式
                else {
                    try {
                        Class<?> paramsClass = parameters.getClass()
                        Method getRegisterInfosMethod = paramsClass.getMethod('getRegisterInfo')
                        def registerInfosProperty = getRegisterInfosMethod.invoke(parameters)
                        if (registerInfosProperty != null) {
                            Method getMethod = registerInfosProperty.getClass().getMethod('get')
                            registerInfoStrings.add(getMethod.invoke(registerInfosProperty) as String)
                        }
                    } catch (Exception ignored) {
                        // 忽略异常，保持向后兼容性
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，保持向后兼容性
        }
        
        // 确保registerInfoStrings不为null
        if (registerInfoStrings == null) {
            registerInfoStrings = new ArrayList<>()
        }
        
        // 如果没有注册信息，跳过扫描
        if (registerInfoStrings.isEmpty()) {
//            println "[AutoRegister] Skipping instrumentation as no register info available"
            return false
        }
        
        // 输出详细的注册信息配置
//        println "[AutoRegister] Plugin is ENABLED with ${registerInfoStrings.size()} registration configurations:"
        
        for (String infoStr : registerInfoStrings) {
            RegisterInfo info = new RegisterInfo()
            info.init(infoStr)
            
//            println "[AutoRegister]   Registration:"
//            println "[AutoRegister]     Interface: ${info.interfaceName ?: 'none'}"
//            if (info.superClassNames) {
//                println "[AutoRegister]     Super classes: ${info.superClassNames}"
//            }
//            if (info.hasInitClass) {
//                println "[AutoRegister]     Init class: ${info.initClassName}"
//                println "[AutoRegister]     Register method: ${info.registerMethodName}(${info.registerMethodDesc})"
//            }
            
            // 使用临时创建的CodeScanProcessor检查是否需要扫描
            CodeScanProcessor scanProcessor = new CodeScanProcessor()
            boolean shouldScan = scanProcessor.shouldScanClass(classData.className, info)
//            println "[AutoRegister] Class ${classData.className} should be scanned for ${info.interfaceName ?: info.superClassNames}: ${shouldScan}"
            
            if (info.hasInitClass || shouldScan) {
                println "[AutoRegister] Class ${classData.className} is instrumentable"
                return true
            }
        }
        
        println "[AutoRegister] Class ${classData.className} is NOT instrumentable"
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
                println "[AutoRegister] Created RegisterClassVisitor for class processing"
            }

            @Override
            void visit(int version, int access, String name, String signature, 
                       String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces)
                this.className = name
                println "[AutoRegister] Visiting class: ${name}, super: ${superName}"
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
                        println "[AutoRegister] Found target method ${name}${descriptor} in class ${className} for registration"
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
                println "[AutoRegister] Created RegisterMethodVisitor for code injection"
            }

            @Override
            void visitInsn(int opcode) {
                // 在返回指令前插入代码
                if (opcode == Opcodes.RETURN) {
                    println "[AutoRegister] Injecting registration code before RETURN instruction"
                    injectRegisterCode()
                }
                super.visitInsn(opcode)
            }

            /**
             * 注入注册代码
             */
            private void injectRegisterCode() {
                println "[AutoRegister] Starting code injection for ${info.classList.size()} classes"
                // 为每个需要注册的类生成注册代码
                for (String clazzName : info.classList) {
                    try {
                        println "[AutoRegister] Injecting registration for class: ${clazzName}"
                        // 创建类实例
                        mv.visitTypeInsn(Opcodes.NEW, clazzName.replace('.', '/'))
                        mv.visitInsn(Opcodes.DUP)
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, clazzName.replace('.', '/'), "<init>", "()V", false)
                        
                        // 调用注册方法
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, info.initClassName.replace('.', '/'),
                                info.registerMethodName, info.registerMethodDesc, false)
                    } catch (Exception e) {
                        println "[AutoRegister] Error during injection for ${clazzName}: ${e.getMessage()}"
                        e.printStackTrace()
                    }
                }
                println "[AutoRegister] Code injection completed"
            }
        }
}