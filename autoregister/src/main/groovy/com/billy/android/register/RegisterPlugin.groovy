package com.billy.android.register

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.instrumentation.InstrumentationScope
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.billy.android.register.RegisterInfo
import com.billy.android.register.RegisterTransform
import com.billy.android.register.AutoRegisterConfig
import org.gradle.api.provider.Property

import java.lang.reflect.Method
import java.lang.reflect.Field

/**
 * 自动注册插件入口
 * @author billy.qi
 * @since 17/3/14 17:35
 */
class RegisterPlugin implements Plugin<Project> {
    public static final String EXT_NAME = 'autoregister'

    @Override
    void apply(Project project) {
        /**
         * 注册transform接口
         */
        project.extensions.create(EXT_NAME, AutoRegisterConfig)
        project.logger.info("[AutoRegister] Plugin initialized in project: ${project.name}")
        
        // 查找Android应用组件扩展
        def androidComponents = project.extensions.findByType(ApplicationAndroidComponentsExtension)
        if (androidComponents) {
            project.logger.info("[AutoRegister] Found ApplicationAndroidComponentsExtension, applying plugin")
            println 'project(' + project.name + ') apply auto-register plugin'
            def transformImpl = new RegisterTransform(project)
            
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                project.logger.info("[AutoRegister] Processing variant: ${variant.name}")
                // 初始化配置
                init(project, transformImpl)
                project.logger.info("[AutoRegister] Configuration initialized for variant: ${variant.name}, registerInfo count: ${transformImpl.config.list.size()}")
                
                // 使用AGP 8.x的方式注册Transform
                variant.instrumentation.transformClassesWith(RegisterAsmClassVisitorFactory.class, 
                        InstrumentationScope.ALL) { params ->
                    project.logger.info("[AutoRegister] Configuring ClassVisitorFactory for variant: ${variant.name}")
                    // 传递配置给ClassVisitorFactory
                    List<String> registerInfoStrings = new ArrayList<>()
                    for (RegisterInfo info : transformImpl.config.list) {
                        registerInfoStrings.add(info.toString())
                        // 打印详细的注册信息
                        println "[AutoRegister] Register info: ${info.toString()}"
                        project.logger.debug("[AutoRegister] Added register info: ${info.interfaceName ?: info.superClassNames}")
                    }
                    
                    // AGP 8.x 兼容的参数设置方式
                    // 使用多层次策略确保参数正确设置
                    setParametersSafely(params, registerInfoStrings)
                }
            }
        }
    }

    static void init(Project project, RegisterTransform transformImpl) {
        AutoRegisterConfig config = project.extensions.findByName(EXT_NAME) as AutoRegisterConfig
        println("[AutoRegister] Initializing configuration for project: ${project.name}")
        project.logger.info("[AutoRegister] Initializing configuration for project: ${project.name}")
        config.project = project
        config.convertConfig()
        transformImpl.config = config
    }
    
    /**
     * 安全地设置参数，支持AGP 7.x和AGP 8.x
     */
    private static void setParametersSafely(Object params, List<String> registerInfoStrings) {
        println "[AutoRegister] Starting setParametersSafely: params=${params?.getClass()?.getName()}, registerInfoCount=${registerInfoStrings?.size()}"
        boolean paramsSet = false
        
        try {
            if (params == null) {
                println "[AutoRegister] ERROR: params is NULL!"
                return
            }
            
            // 策略1: 尝试使用Java反射API调用setter方法
            try {
                Class<?> paramsClass = params.getClass()
                println "[AutoRegister] Params class: ${paramsClass.getName()}"
                
                // 尝试设置registerInfos
                try {
                    Method setRegisterInfosMethod = findMethod(paramsClass, 'setRegisterInfo', String)
                    Method setRegisterInfosMethodProperty = findMethod(paramsClass, 'setRegisterInfo', Property)
                    println "[AutoRegister] setRegisterInfosMethodProperty method is null: ${setRegisterInfosMethodProperty == null}"
                    if (setRegisterInfosMethod != null) {
                        println "[AutoRegister] Found setRegisterInfo method: ${setRegisterInfosMethod.getName()}"
                        setRegisterInfosMethod.invoke(params, registerInfoStrings.get(0))
                        println "[AutoRegister] Successfully invoked setRegisterInfo"
                        
                        // 尝试设置enabled
                        Method setEnabledMethod = findMethod(paramsClass, 'setEnabled', boolean.class)
                        if (setEnabledMethod != null) {
                            println "[AutoRegister] Found setEnabled method: ${setEnabledMethod.getName()}"
                            setEnabledMethod.invoke(params, true)
                            println "[AutoRegister] Successfully invoked setEnabled"
                            paramsSet = true
                        }
                    }
                } catch (Exception e) {
                    println "[AutoRegister] Strategy 1 failed: ${e.getMessage()}"
                }
                
                // 如果策略1失败，尝试策略2: 通过字段访问设置值
                if (!paramsSet) {
                    println "[AutoRegister] Trying strategy 2: Field access"
                    // 尝试获取并设置registerInfos字段
                    Field registerInfosField = findField(paramsClass, 'registerInfos')
                    if (registerInfosField != null) {
                        println "[AutoRegister] Found registerInfos field"
                        registerInfosField.setAccessible(true)
                        def fieldValue = registerInfosField.get(params)
                        println "[AutoRegister] registerInfos field value type: ${fieldValue?.getClass()?.getName()}"
                        
                        // 如果fieldValue是Provider类型，尝试设置其值
                        if (fieldValue != null) {
                            try {
                                Method setMethod = findMethod(fieldValue.getClass(), 'set', List)
                                if (setMethod != null) {
                                    println "[AutoRegister] Found set method on field value"
                                    setMethod.invoke(fieldValue, registerInfoStrings)
                                } else {
                                    // 尝试addAll方法
                                    Method addAllMethod = findMethod(fieldValue.getClass(), 'addAll', Collection)
                                    if (addAllMethod != null) {
                                        println "[AutoRegister] Found addAll method on field value"
                                        addAllMethod.invoke(fieldValue, registerInfoStrings)
                                    } else {
                                        // 直接设置字段值
                                        println "[AutoRegister] Setting field value directly"
                                        registerInfosField.set(params, registerInfoStrings)
                                    }
                                }
                                paramsSet = true
                            } catch (Exception ex) {
                                println "[AutoRegister] Error in field access: ${ex.getMessage()}"
                                try {
                                    registerInfosField.set(params, registerInfoStrings)
                                    paramsSet = true
                                } catch (Exception ex2) {
                                    println "[AutoRegister] Failed to set field value directly: ${ex2.getMessage()}"
                                }
                            }
                        }
                        
                        // 尝试获取并设置enabled字段
                        Field enabledField = findField(paramsClass, 'enabled')
                        if (enabledField != null) {
                            println "[AutoRegister] Found enabled field"
                            enabledField.setAccessible(true)
                            def enabledValue = enabledField.get(params)
                            println "[AutoRegister] enabled field value type: ${enabledValue?.getClass()?.getName()}"
                            
                            // 如果enabledValue是Provider类型，尝试设置其值
                            if (enabledValue != null) {
                                try {
                                    Method setMethod = findMethod(enabledValue.getClass(), 'set', boolean.class)
                                    if (setMethod != null) {
                                        println "[AutoRegister] Found set method on enabled field value"
                                        setMethod.invoke(enabledValue, true)
                                        paramsSet = true
                                    }
                                } catch (Exception ex) {
                                    println "[AutoRegister] Error setting enabled field: ${ex.getMessage()}"
                                    try {
                                        // 直接设置字段值
                                        enabledField.set(params, true)
                                        paramsSet = true
                                    } catch (Exception ex2) {
                                        println "[AutoRegister] Failed to set enabled field directly: ${ex2.getMessage()}"
                                    }
                                }
                            }
                        }
                    } else {
                        println "[AutoRegister] registerInfos field not found"
                    }
                }
            } catch (Exception e) {
                println "[AutoRegister] Error in setParametersSafely: ${e.getMessage()}"
                e.printStackTrace()
                // 所有策略都失败，尝试使用Groovy的动态特性
                try {
                    // 使用Groovy的动态能力尝试多种方式
                    if (params.hasProperty('setParameters')) {
                        println "[AutoRegister] Trying Groovy dynamic: setParameters"
                        params.setParameters(['registerInfos': registerInfoStrings, 'enabled': true])
                        paramsSet = true
                    } else if (params.metaClass.respondsTo(params, 'setProperty')) {
                        println "[AutoRegister] Trying Groovy dynamic: setProperty"
                        params.setProperty('registerInfos', registerInfoStrings)
                        params.setProperty('enabled', true)
                        paramsSet = true
                    } else {
                        // 最极端的情况，尝试直接赋值
                        println "[AutoRegister] Trying Groovy dynamic: direct assignment"
                        params.'registerInfos' = registerInfoStrings
                        params.'enabled' = true
                        paramsSet = true
                    }
                } catch (Exception ex) {
                    println "[AutoRegister] Critical error: Failed to set parameters in all attempted ways"
                    ex.printStackTrace()
                }
            }
        } catch (Exception e) {
            println "[AutoRegister] Unexpected error setting parameters: ${e.getMessage()}"
            e.printStackTrace()
        }
        
        println "[AutoRegister] Parameters set: ${paramsSet}"
        if (!paramsSet) {
            println "[AutoRegister] WARNING: ALL parameter setting strategies failed! This may cause the plugin to not work properly."
            println "[AutoRegister] Check your AGP version compatibility."
        }    }
    
    /**
     * 安全地查找方法，避免NoSuchMethodException
     */
    private static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            println "[AutoRegister] Trying to find method: ${methodName} with ${parameterTypes.length} parameters"
            println "[AutoRegister] all methods: ${clazz.methods.collect { "${it.name}:" + it.parameterTypes.collect { it.name }.join(', ') }.join(', ')}"
            return clazz.getMethod(methodName, parameterTypes)
        } catch (NoSuchMethodException e) {
            // 尝试查找所有方法，包括父类的
            Class<?> currentClass = clazz
            while (currentClass != null) {
                try {
                    return currentClass.getDeclaredMethod(methodName, parameterTypes)
                } catch (NoSuchMethodException ignored) {}
                currentClass = currentClass.getSuperclass()
            }
            return null
        }
    }
    
    /**
     * 安全地查找字段，避免NoSuchFieldException
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getField(fieldName)
        } catch (NoSuchFieldException e) {
            // 尝试查找所有字段，包括父类的和私有字段
            Class<?> currentClass = clazz
            while (currentClass != null) {
                try {
                    return currentClass.getDeclaredField(fieldName)
                } catch (NoSuchFieldException ignored) {}
                currentClass = currentClass.getSuperclass()
            }
            return null
        }
    }
}
