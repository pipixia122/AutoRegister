package com.billy.android.register

import org.gradle.api.Project

/**
 * AGP 8.x 兼容的 RegisterTransform
 * 保留核心功能，但不再实现旧的 Transform 接口
 * @author billy.qi
 * @since 17/3/14 17:39
 */
public class RegisterTransform {
    Project project
    AutoRegisterConfig config

    RegisterTransform(Project project) {
        this.project = project
    }

    /**
     * 在 AGP 8.x 中，Transform 的主要功能已由 RegisterAsmClassVisitorFactory 接管
     * 此类保留核心配置逻辑，作为兼容层
     */
    
    /**
     * 获取转换名称
     */
    String getName() {
        return "auto-register"
    }
    
    /**
     * 检查是否启用增量构建（在 AGP 8.x 中通常由新 API 自动处理）
     */
    boolean isIncremental() {
        return false
    }
}