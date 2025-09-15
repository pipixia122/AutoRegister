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
                    params.registerInfos.set(registerInfoStrings)
                    params.enabled.set(true)
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
