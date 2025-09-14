package com.billy.android.register

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.regex.Pattern

/**
 * AGP 8.x 兼容的代码扫描处理器
 * @author billy.qi
 * @since 17/3/20 11:48
 */
class CodeScanProcessor {

    ArrayList<RegisterInfo> infoList
    Map<String, ScanJarHarvest> cacheMap
    Set<String> cachedJarContainsInitClass = new HashSet<>()

    // 添加无参构造函数，用于AGP 8.x的调用方式
    CodeScanProcessor() {
        this.infoList = new ArrayList<>()
        this.cacheMap = new HashMap<>()
    }
    
    // 保留原有的构造函数，用于向后兼容
    CodeScanProcessor(ArrayList<RegisterInfo> infoList, Map<String, ScanJarHarvest> cacheMap) {
        this.infoList = infoList
        this.cacheMap = cacheMap
    }

    // 设置注册信息列表，用于在AGP 8.x环境中动态设置
    void setInfoList(ArrayList<RegisterInfo> infoList) {
        this.infoList = infoList
    }

    /**
     * 检查是否应该扫描此类
     */
    boolean shouldScanClass(String className, RegisterInfo info) {
        if (className == null) {
            return false
        }
        
        // 检查是否需要处理此类
        if (!shouldProcessThisClassForRegister(info, className)) {
            return false
        }
        
        // 检查排除模式
        if (info.excludePatterns != null) {
            for (Pattern excludePattern : info.excludePatterns) {
                if (excludePattern.matcher(className).matches()) {
                    return false
                }
            }
        }
        
        return true
    }

    /**
     * 扫描jar包
     * @param jarFile 来源jar包文件
     */
    boolean scanJar(File jarFile) {
        if (!jarFile) {
            return false
        }

        def srcFilePath = jarFile.absolutePath
        def file = new JarFile(jarFile)
        Enumeration enumeration = file.entries()

        while (enumeration.hasMoreElements()) {
            JarEntry jarEntry = enumeration.nextElement()
            String entryName = jarEntry.getName()
            
            //support包不扫描
            if (entryName.startsWith("android/support")) {
                break
            }
            
            // 直接处理类文件
            if (entryName.endsWith(".class")) {
                try {
                    InputStream inputStream = file.getInputStream(jarEntry)
                    scanClass(inputStream, srcFilePath, entryName)
                    inputStream.close()
                } catch (Exception e) {
                    e.printStackTrace()
                }
            }
        }
        
        if (file != null) {
            file.close()
        }
        
        return true
    }

    /**
     * 检查并设置初始化类
     */
    boolean checkInitClass(String entryName) {
        if (entryName == null || !entryName.endsWith(".class")) {
            return false
        }
        
        String className = entryName.substring(0, entryName.lastIndexOf('.'))
        boolean found = false
        
        infoList.each { ext ->
            if (ext.initClassName == className) {
                // 在 AGP 8.x 中，我们不需要直接记录文件路径
                // 而是通过 ClassVisitor 处理时直接注入代码
                ext.hasInitClass = true
                found = true
            }
        }
        
        return found
    }

    /**
     * 检查是否应该处理此类
     */
    boolean shouldProcessClass(String entryName) {
        if (entryName == null || !entryName.endsWith(".class")) {
            return false
        }
        
        // 转换为内部类名格式 (去掉 .class 后缀)
        String internalClassName = entryName.substring(0, entryName.lastIndexOf('.'))
        
        for (RegisterInfo info : infoList) {
            if (shouldProcessThisClassForRegister(info, internalClassName)) {
                return true
            }
        }
        
        return false
    }

    /**
     * 过滤器进行过滤
     */
    private static boolean shouldProcessThisClassForRegister(RegisterInfo info, String entryName) {
        if (info != null && info.includePatterns != null) {
            for (Pattern pattern : info.includePatterns) {
                if (pattern.matcher(entryName).matches()) {
                    // 检查排除模式
                    if (info.excludePatterns != null) {
                        for (Pattern excludePattern : info.excludePatterns) {
                            if (excludePattern.matcher(entryName).matches()) {
                                return false
                            }
                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    /**
     * 处理class的注入
     */
    boolean scanClass(InputStream inputStream, String filePath, String entryName) {
        try {
            // 转换为内部类名格式
            String className = entryName.substring(0, entryName.lastIndexOf('.'))
            
            // 检查是否需要处理此类
            if (!shouldProcessClass(entryName)) {
                return false
            }
            
            ClassReader cr = new ClassReader(inputStream)
            ScanClassVisitor cv = new ScanClassVisitor(Opcodes.ASM9, className)
            cr.accept(cv, ClassReader.EXPAND_FRAMES)
            
            return cv.found
        } catch (Exception e) {
            e.printStackTrace()
            return false
        } finally {
            try {
                inputStream.close()
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * ASM类访问器，用于扫描类的继承关系
     */
    class ScanClassVisitor extends ClassVisitor {
        private String className
        private boolean found = false

        ScanClassVisitor(int api, String className) {
            super(api)
            this.className = className
        }

        boolean is(int access, int flag) {
            return (access & flag) == flag
        }

        boolean isFound() {
            return found
        }

        @Override
        void visit(int version, int access, String name, String signature,
                   String superName, String[] interfaces) {
            // 跳过抽象类、接口和非public类
            if (is(access, Opcodes.ACC_ABSTRACT) ||
                    is(access, Opcodes.ACC_INTERFACE) ||
                    !is(access, Opcodes.ACC_PUBLIC)) {
                return
            }
            
            // 转换为点分隔的类名格式
            String dotClassName = name.replace('/', '.')
            
            infoList.each { info ->
                if (shouldProcessThisClassForRegister(info, name)) {
                    // 检查超类
                    if (superName != null && superName != 'java/lang/Object' && !info.superClassNames.isEmpty()) {
                        for (String superClassName : info.superClassNames) {
                            if (superName.equals(superClassName.replace('.', '/'))) {
                                info.classList.add(dotClassName)
                                found = true
                                return
                            }
                        }
                    }
                    
                    // 检查接口
                    if (info.interfaceName != null && interfaces != null) {
                        String interfaceInternalName = info.interfaceName.replace('.', '/')
                        for (String itName : interfaces) {
                            if (itName.equals(interfaceInternalName)) {
                                info.classList.add(dotClassName)
                                found = true
                                return
                            }
                        }
                    }
                }
            }
        }
    }
}