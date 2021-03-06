package com.wtgroup.sugar.db;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 父子, 属性表数据处理
 *
 * <pre>
 *  id pid ...
 *  1  null
 *  2  1
 *  3  1
 *  4  2
 *  5  2
 *  6  3
 *  ...
 *
 *
 * </pre>
 * 这类表可以不用自关联, 原样输入进来, 然后处理成java的树形结构.
 * 实际场景中, 如果不需要整张表的数据, 需要保证有筛选的策略.
 * 筛出需要的记录.
 * 1. 分析出树形关系.
 * 1->[2,3]; 2->[4,5]; 3->[6]; 9->null
 * 2. 递归封装
 * <p>
 * Note:
 * id 不允许重复, 避免出现循环引用. 树形关系将乱套.
 *
 * @author L&J
 * @version 0.0.0
 * @email liuhejun108@163.com
 * @date 2019/7/14 21:23
 */
@Slf4j
public class TreeTableHandler<T, R, ID> {

    @Setter
    private List<T> data;
    @Setter
    private Function<T, ID> getId;  // 假定 id 和 pid 列类型一致
    @Setter
    private Function<T, ID> getPId;
    //private Function<T, R> rowMapper;
    @Setter
    private ResultMapper<T, R>       resultMapper;
    private Predicate<T> isRootNode;

    // @Getter
    private Map<ID, List<ID>> treeMeta;
    /**
     * 原始数据转换成 id 为key的map
     */
    private Map<ID, T> dataMap;
    // @Getter
    private List<R> results;
    private HashSet<ID> transed = new HashSet<>();

    public TreeTableHandler(List<T> data, Function<T, ID> getId, Function<T, ID> getPId, ResultMapper<T, R> resultMapper) {
        this(data, getId, getPId, resultMapper, null);
    }

    public TreeTableHandler(List<T> data, Function<T, ID> getId, Function<T, ID> getPId, ResultMapper<T, R> resultMapper,
                            Predicate<T> isRootNode) {
        this.data = data;
        this.getId = getId;
        this.getPId = getPId;
        this.resultMapper = resultMapper;
        this.isRootNode = isRootNode;
    }

    private void parseTreeMeta() {
        dataMap = new HashMap<>();
        treeMeta = new LinkedHashMap<>();
        // 原始数据转换成 id 为key的map
        for ( int i = 0; i < data.size(); i++ ) {
            T r = data.get(i);
            if ( r == null ) {
                log.warn("数据行==null, 略过, 行数: " + i);
                continue;
            }

            ID id  = getId.apply(r);
            if ( id != null ) {
                T old = dataMap.put(id, r);
                // id 不允许重复
                if ( old != null ) {
                    throw new RuntimeException("id 不允许重复: id=" + id + ", 第" + i + "行");
                }
            }
        }

        // treeMeta
        for ( int i = 0; i < data.size(); i++ ) {
            T r = data.get(i);
            if ( r == null ) {
                //log.warn("数据行==null, 略过, 行数: " + i);
                continue;
            }
            ID id  = getId.apply(r);
            ID pid = getPId.apply(r);
            // 优先使用指定规则判断 根节点.
            // 无指定规则时, pid 不为null, 且在 id 列中有 => 当前 id 是中间节点
            // 否则, 认为是没有子节点的根节点
            if (!isRoot(r, pid)) {
                List<ID> ids = treeMeta.computeIfAbsent(pid, k -> new ArrayList<>());
                if (id != null && !id.equals(pid)) {
                    ids.add(id);
                } else if (id == null) {
                    log.warn("id == null, 忽略! 行数: " + i);
                } else if (id.equals(pid)) {
                    // id == pid 时, 禁止添加到子集中, 否则必会导致循环引用
                    log.warn("id == pid, 忽略! id=" + id);
                }
            } else { // 当前 id 是顶级, 且没有子节点了
                treeMeta.put(id, null);
            }

        }

    }

    private boolean isRoot(T r, ID pid) {
        boolean isRoot = false;
        if (isRootNode != null) {
            isRoot = isRootNode.test(r);
        }else{
            isRoot = pid == null || !dataMap.containsKey(pid);
        }

        return isRoot;
    }

    /*? 加了 isRootNode 判断貌似没有用. 利用 treeMeta 处理 tree 过程中, 没有上级的 pid 不会别人处理到.
    * 就不会放到 transed 集合中. 这样, 到自己时, 依然往下执行. 就还在 root 层级上了.*/

    public List<R> toTree() {

        if (treeMeta==null) {
            this.parseTreeMeta();
        }
        if (results!=null) {
            return results;
        }
        // 检验引用链 (貌似禁止id列重复, 已经避免了循环引用)
        // this.validateCircleRefer();

        results = new ArrayList<>();

        for ( Map.Entry<ID, List<ID>> m : treeMeta.entrySet() ) {
            // key 去 dataMap 中拿到 行数据, 转换成 javabean
            // 遍历value(list) 递归
            ID key = m.getKey();
            if ( transed.contains(key) ) {
                continue;
            }
            R  res = toTree0(key,0, Optional.empty());
            if ( res != null ) {
                results.add(res);
            }
            transed.add(key);
        }
        return results;
    }

    private R toTree0( ID id, int lvl, Optional<R> parent ) {
        T row = dataMap.get(id);
        // row 有可能为空, 因为 pid 在id列中没有, 而是指向其他表, 或者错误
        if ( row == null ) {
            log.debug("id( " + id + " )对应的数据行不存在");
            return null;
        }

        // parent 参数的作用: 自顶向下, 可将祖先的信息一直传递到叶子
        R        res      = resultMapper.mapProperties(row, lvl, parent);
        List<ID> childIds = treeMeta.get(id);
        if ( childIds != null && childIds.size() > 0) {
            List<R> children = new ArrayList<>();
            for ( ID cid : childIds ) {
                R e = toTree0(cid, lvl+1, Optional.of(res));
                if (e!=null) {
                    children.add(e);
                }
                transed.add(cid);
            }
            // 设置 children
            // 对子集数据聚合运算, 并将结果封装进 parent
            resultMapper.onChildren(res, children);
        }else {
            resultMapper.onChildren(res, null);
        }
        return res;
    }

    public Map<ID, List<ID>> getTreeMeta() {
        if (treeMeta==null) {
            this.parseTreeMeta();
        }
        return treeMeta;
    }

    public List<R> getResults() {
        if (results==null) {
            this.toTree();
        }
        return results;
    }

    private void validateCircleRefer() {

        for ( Map.Entry<ID, List<ID>> e : treeMeta.entrySet() ) {
            // 校验引用错乱用的
            Set<ID> referLink = new HashSet<>();
            // 校验循环引用的, 同一个分支上不允许出现两次一样的节点
            Set<ID> pidSet= new HashSet<>();
            pidSet.add(e.getKey());
            validateCircleRefer0(e.getKey(), referLink, pidSet);
        }



    }

    private void validateCircleRefer0(ID key, Set<ID> referLink, Set<ID> pidSet ) {
        referLink.add(key);
        List<ID> ids = treeMeta.get(key);
        if ( ids==null ) {
            return;
        }

        for ( ID id : ids ) {
            if ( pidSet.contains(id) ) {
                throw new RuntimeException("出现循环引用: "+pidSet + " -> " + id);
            }

            if(referLink.contains(id)){
                throw new RuntimeException("引用错乱, 同一节点只能有唯一的父节点: "+referLink + " -> " + id);
            }
            Set<ID> pidSetClone = new HashSet<>(pidSet);
            pidSetClone.add(id);  // 增加当前节点
            validateCircleRefer0(id, referLink, pidSetClone);

        }
    }

    /**
     * 数据行转换为结果
     * 1. 普通属性转换;
     * 2. 子集封装, 聚合计算.
     *
     * @param <T> 原数据行类型. 如数据库查询出的行实体类
     * @param <R> 转换后的类型. 可以原样输出(设置 T==R)
     */
    public static interface ResultMapper<T, R> {

        /**
         * 数据行 ==> javabean
         * 如无需转换, 类型, 可将设置 T==R, 这里原样返回.
         * @param row 关系表的数据行
         * @param lvl 当前层级. 0 开始
         * @param parent 父节点, 自顶向下, 可将祖先的信息一直传递到叶子
         * @return 定制行数据结果封装
         */
        R mapProperties(T row, int lvl, Optional<R> parent);

        /**
         * 当子集数据准备好后调用. <em>通常你需要</em>, <code>parent.setChildren(children)</code>.
         * 1. 决定了处理好的子集如何封装. 如设置哪个字段.
         * 2. 可以对子集的进行聚合计算, 比如子集个数, 子集的某个属性求和..., 并设置和父上级对象的某个属性.
         *
         * @param parent 已经封装好的结果对象
         * @param children 已经封装好的子集结果对象List
         */
        void onChildren(R parent, List<R> children);

    }


}
