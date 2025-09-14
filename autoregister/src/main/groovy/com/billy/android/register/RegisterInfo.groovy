package com.billy.android.register

import org.objectweb.asm.Opcodes

import java.util.regex.Pattern

/**
 * aop的配置信息
 * @author billy.qi
 * @since 17/3/28 11:48
 */
class RegisterInfo {
    static final DEFAULT_EXCLUDE = [
            '.*/R(\\$[^/]*)?'
            , '.*/BuildConfig$'
    ]
    //以下是可配置参数
    String interfaceName = ''
    ArrayList<String> superClassNames = []
    String initClassName = ''
    String initMethodName = '' // 用于向后兼容，对应于新的 methodName
    String methodName = '' // AGP 8.x 版本使用的方法名
    String registerClassName = ''
    String registerMethodName = ''
    String registerMethodDesc = '(Ljava/lang/Object;)V' // 注册方法描述符
    String methodDesc = '()V' // 目标方法描述符
    ArrayList<String> include = []
    ArrayList<String> exclude = []
    int amsApiVersion = Opcodes.ASM6

    //以下不是可配置参数
    List<Pattern> includePatterns = []
    List<Pattern> excludePatterns = []
    File fileContainsInitClass //initClassName的class文件或含有initClassName类的jar文件
    List<String> classList = new ArrayList<>()
    boolean hasInitClass = false // 标记是否找到初始化类

    RegisterInfo() {}

    void reset() {
        fileContainsInitClass = null
        classList.clear()
        hasInitClass = false
    }

    boolean validate() {
        return interfaceName && registerClassName && registerMethodName
    }

    //用于在console中输出日志
    @Override
    String toString() {
        StringBuilder sb = new StringBuilder('{')
        sb.append('\n\t').append('scanInterface').append('\t\t\t=\t').append(interfaceName)
        sb.append('\n\t').append('scanSuperClasses').append('\t\t=\t[')
        for (int i = 0; i < superClassNames.size(); i++) {
            if (i > 0) sb.append(',')
            sb.append(' \'').append(superClassNames.get(i)).append('\'')
        }
        sb.append(' ]')
        sb.append('\n\t').append('codeInsertToClassName').append('\t=\t').append(initClassName)
        sb.append('\n\t').append('codeInsertToMethodName').append('\t=\t').append(initMethodName)
        sb.append('\n\t').append('registerMethodName').append('\t\t=\tpublic static void ')
                .append(registerClassName).append('.').append(registerMethodName)
        sb.append('\n\t').append('include').append(' = [')
        include.each { i ->
            sb.append('\n\t\t\'').append(i).append('\'')
        }
        sb.append('\n\t]')
        sb.append('\n\t').append('exclude').append(' = [')
        exclude.each { i ->
            sb.append('\n\t\t\'').append(i).append('\'')
        }
        sb.append('\n\t]')
        sb.append('\n\t').append('amsApiVersion').append('\t\t\t=\t').append(amsApiVersion)
        sb.append('\n}')
        return sb.toString()
    }

    void init(String infoStr) {
        // 解析字符串格式的配置信息
        if (!infoStr) {
            init()
            return
        }
        
        try {
            // 解析scanInterface
            def interfaceMatch = infoStr =~ /scanInterface\s*=\s*(.+)/
            if (interfaceMatch.find()) {
                interfaceName = interfaceMatch.group(1).trim()
            }
            
            // 解析scanSuperClasses
            def superClassMatch = infoStr =~ /scanSuperClasses\s*=\s*\[(.*?)\]/
            if (superClassMatch.find()) {
                superClassNames = new ArrayList<String>()
                def superClassStr = superClassMatch.group(1).trim()
                if (superClassStr) {
                    superClassStr.split(',').each { className ->
                        className = className.trim().replaceAll(/'/, '').trim()
                        if (className) {
                            superClassNames.add(className)
                        }
                    }
                }
            }
            
            // 解析codeInsertToClassName
            def initClassMatch = infoStr =~ /codeInsertToClassName\s*=\s*(.+)/
            if (initClassMatch.find()) {
                initClassName = initClassMatch.group(1).trim()
            }
            
            // 解析codeInsertToMethodName
            def initMethodMatch = infoStr =~ /codeInsertToMethodName\s*=\s*(.+)/
            if (initMethodMatch.find()) {
                initMethodName = initMethodMatch.group(1).trim()
            }
            
            // 解析registerMethodName
            def registerMethodMatch = infoStr =~ /registerMethodName\s*=\s*public static void\s+([^\.]+)\.([^\s]+)/
            if (registerMethodMatch.find()) {
                registerClassName = registerMethodMatch.group(1).trim()
                registerMethodName = registerMethodMatch.group(2).trim()
            }
            
            // 解析amsApiVersion
            def apiVersionMatch = infoStr =~ /amsApiVersion\s*=\s*(\d+)/
            if (apiVersionMatch.find()) {
                amsApiVersion = apiVersionMatch.group(1).trim() as Integer
            }
            
            // 调用无参init方法完成剩余初始化
            init()
        } catch (Exception e) {
            // 如果解析失败，使用默认值初始化
            init()
        }
    }

    void init() {
        if (include == null) include = new ArrayList<>()
        if (include.empty) include.add(".*") //如果没有设置则默认为include所有
        if (exclude == null) exclude = new ArrayList<>()
        if (!registerClassName)
            registerClassName = initClassName

        //将interfaceName中的'.'转换为'/'
        if (interfaceName)
            interfaceName = convertDotToSlash(interfaceName)
        //将superClassName中的'.'转换为'/'
        if (superClassNames == null) superClassNames = new ArrayList<>()
        for (int i = 0; i < superClassNames.size(); i++) {
            def superClass = convertDotToSlash(superClassNames.get(i))
            superClassNames.set(i, superClass)
        }
        //注册和初始化的方法所在的类默认为同一个类
        initClassName = convertDotToSlash(initClassName)
        //默认插入到static块中
        if (!initMethodName)
            initMethodName = "<clinit>"
        
        // AGP 8.x 兼容：设置 methodName 和 methodDesc
        if (!methodName) {
            methodName = initMethodName
        }
        
        registerClassName = convertDotToSlash(registerClassName)
        //添加默认的排除项
        DEFAULT_EXCLUDE.each { e ->
            if (!exclude.contains(e))
                exclude.add(e)
        }
        initPattern(include, includePatterns)
        initPattern(exclude, excludePatterns)
    }

    private static String convertDotToSlash(String str) {
        return str ? str.replaceAll('\\.', '/').intern() : str
    }

    private static void initPattern(List<String> list, List<Pattern> patterns) {
        list.each { s ->
            patterns.add(Pattern.compile(s))
        }
    }
}