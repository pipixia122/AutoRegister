package com.billy.android.register

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.instrumentation.InstrumentationScope
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.billy.android.register.RegisterInfo
import com.billy.android.register.RegisterTransform
import com.billy.android.register.AutoRegisterConfig
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
        config.project = project
        config.convertConfig()
        transformImpl.config = config
    }
    
    /**
     * 安全地设置参数，支持AGP 7.x和AGP 8.x
     */
    private static void setParametersSafely(Object params, List<String> registerInfoStrings) {
        boolean paramsSet = false
        
        try {
            // 策略1: 尝试使用Java反射API调用setter方法
            try {
                Class<?> paramsClass = params.getClass()
                
                // 尝试设置registerInfos
                try {
                    Method setRegisterInfosMethod = findMethod(paramsClass, 'setRegisterInfos', List)
                    if (setRegisterInfosMethod != null) {
                        setRegisterInfosMethod.invoke(params, registerInfoStrings)
                        
                        // 尝试设置enabled
                        Method setEnabledMethod = findMethod(paramsClass, 'setEnabled', boolean.class)
                        if (setEnabledMethod != null) {
                            setEnabledMethod.invoke(params, true)
                            paramsSet = true
                        }
                    }
                } catch (Exception ignored) {}
                
                // 如果策略1失败，尝试策略2: 通过字段访问设置值
                if (!paramsSet) {
                    // 尝试获取并设置registerInfos字段
                    Field registerInfosField = findField(paramsClass, 'registerInfos')
                    if (registerInfosField != null) {
                        registerInfosField.setAccessible(true)
                        def fieldValue = registerInfosField.get(params)
                        
                        // 如果fieldValue是Provider类型，尝试设置其值
                        if (fieldValue != null) {
                            try {
                                Method setMethod = findMethod(fieldValue.getClass(), 'set', List)
                                if (setMethod != null) {
                                    setMethod.invoke(fieldValue, registerInfoStrings)
                                } else {
                                    // 尝试addAll方法
                                    Method addAllMethod = findMethod(fieldValue.getClass(), 'addAll', Collection)
                                    if (addAllMethod != null) {
                                        addAllMethod.invoke(fieldValue, registerInfoStrings)
                                    } else {
                                        // 直接设置字段值
                                        registerInfosField.set(params, registerInfoStrings)
                                    }
                                }
                            } catch (Exception ex) {
                                // 直接设置字段值
                                registerInfosField.set(params, registerInfoStrings)
                            }
                        }
                        
                        // 尝试获取并设置enabled字段
                        Field enabledField = findField(paramsClass, 'enabled')
                        if (enabledField != null) {
                            enabledField.setAccessible(true)
                            def enabledValue = enabledField.get(params)
                            
                            // 如果enabledValue是Provider类型，尝试设置其值
                            if (enabledValue != null) {
                                try {
                                    Method setMethod = findMethod(enabledValue.getClass(), 'set', boolean.class)
                                    if (setMethod != null) {
                                        setMethod.invoke(enabledValue, true)
                                        paramsSet = true
                                    }
                                } catch (Exception ex) {
                                    // 直接设置字段值
                                    enabledField.set(params, true)
                                    paramsSet = true
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 所有策略都失败，尝试使用Groovy的动态特性
                try {
                    // 使用Groovy的动态能力尝试多种方式
                    if (params.hasProperty('setParameters')) {
                        params.setParameters(['registerInfos': registerInfoStrings, 'enabled': true])
                        paramsSet = true
                    } else if (params.metaClass.respondsTo(params, 'setProperty')) {
                        params.setProperty('registerInfos', registerInfoStrings)
                        params.setProperty('enabled', true)
                        paramsSet = true
                    } else {
                        // 最极端的情况，尝试直接赋值
                        params.'registerInfos' = registerInfoStrings
                        params.'enabled' = true
                        paramsSet = true
                    }
                } catch (Exception ex) {
                    println 'Critical error: Failed to set parameters in all attempted ways'
                }
            }
        } catch (Exception e) {
            println 'Unexpected error setting parameters: ' + e.getMessage()
        }
    }
    
    /**
     * 安全地查找方法，避免NoSuchMethodException
     */
    private static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
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
