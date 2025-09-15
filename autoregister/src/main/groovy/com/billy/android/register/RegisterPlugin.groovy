package com.billy.android.register

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.instrumentation.InstrumentationScope
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.billy.android.register.RegisterInfo
/**
 * 自动注册插件入口
 * @author billy.qi
 * @since 17/3/14 17:35
 */
public class RegisterPlugin implements Plugin<Project> {
    public static final String EXT_NAME = 'autoregister'

    @Override
    public void apply(Project project) {
        /**
         * 注册transform接口
         */
        project.extensions.create(EXT_NAME, AutoRegisterConfig)
        
        // 查找Android应用组件扩展
        def androidComponents = project.extensions.findByType(ApplicationAndroidComponentsExtension)
        if (androidComponents) {
            println 'project(' + project.name + ') apply auto-register plugin'
            def transformImpl = new RegisterTransform(project)
            
            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                // 初始化配置
                init(project, transformImpl)
                
                // 使用AGP 8.x的方式注册Transform
                variant.instrumentation.transformClassesWith(RegisterAsmClassVisitorFactory.class, 
                        InstrumentationScope.ALL) { params ->
                    // 传递配置给ClassVisitorFactory
                    List<String> registerInfoStrings = new ArrayList<>()
                    for (RegisterInfo info : transformImpl.config.list) {
                        registerInfoStrings.add(info.toString())
                    }
                    // 在AGP 8.x中，我们需要使用Groovy的动态方法调用
                    // 使用respondsTo()来安全地检查对象是否支持特定方法
                    try {
                        // 尝试直接调用set方法 - 这是AGP 7.x的方式
                        if (params.metaClass.respondsTo(params, 'registerInfos')) {
                            params.registerInfos.set(registerInfoStrings)
                            params.enabled.set(true)
                        }
                        // 如果直接访问属性失败，尝试调用setter方法 - 这是AGP 8.x的方式
                        else if (params.metaClass.respondsTo(params, 'setRegisterInfos')) {
                            params.setRegisterInfos(registerInfoStrings)
                            params.setEnabled(true)
                        }
                        // 如果上述都失败，尝试通过反射设置值
                        else {
                            println 'Warning: Using fallback reflection method to set parameters'
                            def registerInfosField = params.getClass().getDeclaredField('registerInfos')
                            registerInfosField.setAccessible(true)
                            registerInfosField.set(params, registerInfoStrings)
                            
                            def enabledField = params.getClass().getDeclaredField('enabled')
                            enabledField.setAccessible(true)
                            enabledField.set(params, true)
                        }
                    } catch (Exception e) {
                        println 'Error setting parameters: ' + e.getMessage()
                        // 最后尝试使用最原始的方式
                        try {
                            // 这是最通用的方式，尝试直接设置值
                            params.invokeMethod('setRegisterInfos', registerInfoStrings)
                            params.invokeMethod('setEnabled', true)
                        } catch (Exception ex) {
                            println 'Critical error: Failed to set parameters in any way'
                        }
                    }
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
}
