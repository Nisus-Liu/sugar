package com.wtgroup.sugar.reflect;

import com.google.common.base.CaseFormat;
import lombok.Data;

import java.beans.Introspector;
import java.lang.invoke.SerializedLambda;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LambdaUtils
 * <p>
 *  遵循 Java 的字段, getter, setter 命名规范.
 *  错误示例: private boolean isMale, 应为 private boolean male.
 *  ! 特殊格式的字段名少用, 如若需要, 请确保符合预期 !
 * </p>
 *
 * -- 2021年1月12日 --
 * Origin 类的属性改为 final .
 *
 * -- v1.0 2020年7月23日 --
 * 提高易用性, 增加可随时转换变量风格的方法.
 *
 * -- 0.1 --
 * @author dafei
 * @version 1.0
 * @date 2019/11/29 15:44
 */
public class LambdaUtil {
    /**
     * SerializedLambda 反序列化缓存
     */
    private static final Map<Class<?>, WeakReference<SerializedLambda>> FUNC_CACHE = new ConcurrentHashMap<>();
    private static final String PREFIX_IS = "is";
    private static final String PREFIX_GET = "get";
    private static final String PREFIX_SET = "set";
    private static final String WRITE_REPLACE = "writeReplace";

    @Data
    public static class Origin {
        private CaseFormat caseFormat;
        private String fieldName;

        public Origin() {
        }

        public Origin(String fieldName, CaseFormat caseFormat) {
            this.fieldName = fieldName;
            this.caseFormat = caseFormat;
        }

        /**指定要转换的目标格式
         * @param convertFormat
         * @return
         */
        public String to(CaseFormat convertFormat) {
            return this.caseFormat.to(convertFormat, this.fieldName);
        }

        /**输出字段名
         * @return
         */
        @Override
        public String toString() {
            return this.fieldName;
        }
    }

    /**
     * 驼峰风格字段 {@link CaseFormat#LOWER_CAMEL}
     *
     * 等价 {@code of(fn, CaseFormat.LOWER_CAMEL)}
     * Note: 指定风格与实际不符时, 会出现非预期结果.
     * @param fn
     * @param <T>
     * @return
     */
    public static <T> Origin lowerCamel(Fn<T, ?> fn) {
        return of(fn, CaseFormat.LOWER_CAMEL);
    }

    /**
     * 构建包含 CaseFormat 信息的 Origin, 方便后续更多操作.
     * @param fn
     * @param originCaseFormat 字段名原始case格式, 格式枚举: {@link com.google.common.base.CaseFormat}
     * @param <T>
     * @return Origin
     */
    public static <T> Origin of(Fn<T, ?> fn, CaseFormat originCaseFormat) {
        String fname = fieldName(fn);
        Origin fnhd = new Origin(fname, originCaseFormat);
        return fnhd;
    }


    /**获取原始的字段名, 即不做格式转换.
     * @param fn
     * @param <T>
     * @return
     */
    public static <T> String fieldName(Fn<T, ?> fn) {
        // User::getName 和 User::getAge , class 不同
        Class<? extends Fn> clazz = fn.getClass();
        if (!clazz.isSynthetic()) {
            throw new IllegalArgumentException("该方法仅能传入 lambda 表达式产生的合成类");
        }

        SerializedLambda lambdaCache = getSerializedLambda(fn, clazz);

        String getter = lambdaCache.getImplMethodName();
        return methodToProperty(getter);
    }

    /**针对 LOWER_CAMEL POJO 字段直接输出 LOWER_UNDERSCORE 风格的字段名
     *
     * 很多时候想要获取DB字段名, 可以用此方法.
     *
     * 等价 {@code of(fn, CaseFormat.LOWER_CAMEL).to(CaseFormat.LOWER_UNDERSCORE)}
     * @param fn
     * @param <T>
     * @return
     */
    public static <T> String lowerCamelToLowerUnderscore(Fn<T, ?> fn) {
        return of(fn, CaseFormat.LOWER_CAMEL).to(CaseFormat.LOWER_UNDERSCORE);
    }

    /**
     * Get SerializedLambda
     * <p>
     * Q: Function 不行, 自己定义的 Fn 就可以<br>
     * A: 关键是 Serializable 接口, 才有 writeReplace 方法.
     *
     * 如果一个序列化类中含有Object writeReplace()方法，那么实际序列化的对象将是作为 writeReplace 方法返回值的对象，
     * 而且序列化过程的依据是该返回对象的序列化实现。
     * 就是说, A.writeReplace return B, 那么序列化 A 时, 实际序列化的将是 B . 和 A 无关.
     * 正式 "替换写" 的语义.
     * 这样, Fn 作为 Lambda , (1)存在 writeReplace 方法, (2)该方法返回 SerializedLambda .
     * 故, 拿到该方法, 进而可以去到字段名(Lambda元数据之一).
     * @param fn Lambda
     * @param clazz key
     */
    private static SerializedLambda getSerializedLambda(Fn<?, ?> fn, Class<? extends Fn> clazz) {
        return Optional.ofNullable(FUNC_CACHE.get(clazz))
                .map(WeakReference::get)
                .orElseGet(() -> {
                    SerializedLambda lambda = null;
                    try {
                        Method method = clazz.getDeclaredMethod(WRITE_REPLACE);
                        method.setAccessible(Boolean.TRUE);
                        lambda = (SerializedLambda) method.invoke(fn);
                        method.setAccessible(Boolean.FALSE);
                    } catch (Exception e) {
                        throw new RuntimeException("method `writeReplace` call fail, get SerializedLambda of `" + clazz.getName() + "` fail");
                    }
                    FUNC_CACHE.put(clazz, new WeakReference<>(lambda));
                    return lambda;
                });
    }

    /**
     * 参考: {@code org.apache.ibatis.reflection.property.PropertyNamer#methodToProperty}
     * @param name
     * @return
     */
    public static String methodToProperty(String name) {
        if (name.startsWith(PREFIX_IS)) {
            name = name.substring(2);
        } else {
            if (!name.startsWith(PREFIX_GET) && !name.startsWith(PREFIX_SET)) {
                throw new RuntimeException("Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
            }

            name = name.substring(3);
        }

        // if (name.length() == 1 || name.length() > 1 && !Character.isUpperCase(name.charAt(1))) {
        //     name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
        // }
        // 符合 Java getter setter 规范
        name = Introspector.decapitalize(name);
        return name;
    }


    /*
    一般JavaBean属性以小写字母开头，驼峰命名格式，相应的 getter/setter 方法是 get/set 接上首字母大写的属性名。例如：属性名为userName，其对应的getter/setter 方法是 getUserName/setUserName。
    但是，还有一些特殊情况：
    1、如果属性名的第二个字母大写，那么该属性名直接用作 getter/setter 方法中 get/set 的后部分，就是说大小写不变。例如属性名为uName，方法是getuName/setuName。
    2、如果属性名的前两个字母是大写（一般的专有名词和缩略词都会大写），也是属性名直接用作 getter/setter 方法中 get/set 的后部分。例如属性名为URL，方法是getURL/setURL。
    3、如果属性名的首字母大写，也是属性名直接用作 getter/setter 方法中 get/set 的后部分。例如属性名为Name，方法是getName/setName，这种是最糟糕的情况，会找不到属性出错，因为默认的属性名是name。
    4、如果属性名以"is"开头，则getter方法会省掉get，set方法会去掉is。例如属性名为isOK，方法是isOK/setOK。
    需要注意的是有些开发工具自动生成的getter/setter方法，并没有考虑到上面所说的特例情况，会导致bug的产生。
    我们在定义JavaBean的属性名时，应该尽量避免属性名的头两个字母中任意一个为大写以及属性名以"is"开头。
    */
}
