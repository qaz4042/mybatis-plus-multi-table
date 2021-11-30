package com.lzb.mpmt.service.multiwrapper.wrapper;

import com.lzb.mpmt.service.multiwrapper.constant.MultiConstant;
import com.lzb.mpmt.service.multiwrapper.sqlsegment.MultiWrapperSelect;
import com.lzb.mpmt.service.multiwrapper.util.*;
import com.lzb.mpmt.service.multiwrapper.util.json.jackson.JSONUtil;
import com.lzb.mpmt.service.multiwrapper.wrapper.wrappercontent.*;
import com.lzb.mpmt.service.multiwrapper.constant.MultiConstant.JoinTypeEnum;
import com.lzb.mpmt.service.multiwrapper.entity.MultiTableRelation;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 多表联查器
 * 举例说明 主表:user_staff 副表:user_staff_address
 *
 * @param <MAIN>
 * @author Administrator
 */
@Data
@NoArgsConstructor
@Slf4j
@SuppressWarnings("unused")
public class MultiWrapper<MAIN> {

    /**
     * 主表信息
     */
    private MultiWrapperMain<MAIN> wrapperMain;
//    private List<MultiWrapperMainSubWhere<?>> wrapperMainSubWheres = Collections.emptyList();

    /**
     * 副表信息
     */
    private List<MultiWrapperSubAndRelation<?>> wrapperSubAndRelations = new ArrayList<>(8);


    /**
     * 计算SQL时,初始化表关系树
     */
    private MultiTreeNode<IMultiWrapperSubAndRelationTreeNode> relationTree;

    public MultiWrapper(MultiWrapperMain<MAIN> wrapperMain, MultiWrapperSub<?, ?>... subTableWrappers) {
        this.wrapperMain = wrapperMain;
        //默认leftJoin
        Arrays.stream(subTableWrappers).forEach(this::leftJoin);
    }

    public MultiWrapper(MultiWrapperMain<MAIN> wrapperMain, Class<?>... subTableClasses) {
        this.wrapperMain = wrapperMain;
        Arrays.stream(subTableClasses).forEach(subTableClass -> this.leftJoin(MultiWrapperSub.lambda(subTableClass)));
    }

    /**
     * 主表信息
     * 例如 select * from user_staff
     *
     * @return MultiWrapper
     */
    public static <MAIN> MultiWrapper<MAIN> main(MultiWrapperMain<MAIN> wrapperMain) {
        MultiWrapper<MAIN> wrapper = new MultiWrapper<>();
        wrapper.setWrapperMain(wrapperMain);
        return wrapper;
    }

//    /**
//     * 主表信息
//     * 例如 select * from user_staff u
//     * left join user_staff_address a on a.staff_id = u.id
//     * where user_staff_address.del_flag = 0
//     *
//     * @return MultiWrapper
//     */
//    public static <MAIN> MultiWrapper<MAIN> main(MultiWrapperMain<MAIN> wrapperMain, MultiWrapperMainSubWhere<?>... wrapperMainSubWhere) {
//        MultiWrapper<MAIN> wrapper = new MultiWrapper<>();
//        wrapper.setWrapperMain(wrapperMain);
//        if (wrapperMainSubWhere != null) {
//            wrapper.setWrapperMainSubWheres(Arrays.stream(wrapperMainSubWhere).filter(Objects::nonNull).collect(Collectors.toList()));
//        }
//        return wrapper;
//    }

    /***
     * join 副表信息
     *
     * @param subTableWrapper subTableWrapper
     * @return MultiWrapper
     */
    public <SUB> MultiWrapper<MAIN> leftJoin(MultiWrapperSub<SUB, ?> subTableWrapper) {
        return leftJoin(null, subTableWrapper);
    }

    /***
     * join是有顺序的,前后两张表,必须有直接关联
     *
     * @param subTableWrapper subTableWrapper
     * @return MultiWrapper
     */
    public <SUB> MultiWrapper<MAIN> innerJoin(MultiWrapperSub<SUB, ?> subTableWrapper) {
        return innerJoin(null, subTableWrapper);
    }

    /***
     * join是有顺序的,前后两张表,必须有直接关联
     *
     * @param relationCode      {@link MultiTableRelation#getCode()}
     * @param subTableWrapper 副表的select和 on内条件信息
     * @return MultiWrapper
     */
    public <SUB> MultiWrapper<MAIN> leftJoin(String relationCode, MultiWrapperSub<SUB, ?> subTableWrapper) {
        JoinTypeEnum joinType = JoinTypeEnum.left_join;
        return this.getMainMultiWrapper(joinType, relationCode, subTableWrapper);
    }

    public <SUB> MultiWrapper<MAIN> innerJoin(String relationCode, MultiWrapperSub<SUB, ?> subTableWrapper) {
        JoinTypeEnum joinType = JoinTypeEnum.inner_join;
        return this.getMainMultiWrapper(joinType, relationCode, subTableWrapper);
    }

    private <SUB> MultiWrapper<MAIN> getMainMultiWrapper(JoinTypeEnum joinType, String relationCode, MultiWrapperSub<SUB, ?> subTableWrapper) {
        wrapperSubAndRelations.add(new MultiWrapperSubAndRelation<>(joinType, relationCode, subTableWrapper));
        return this;
    }

    /**
     * 输出最终sql
     */
    public String computeAggregateSql() {
        String mainTableName = wrapperMain.getTableName();
        if (mainTableName == null) {
            throw new MultiException("请先通过MultiWrapperMain.lambda(UserInfo.class)或者.eq(UserInfo::getId)确定表名,在执行查询");
        }
        this.loadRelations();

        //全部字段聚合 select sum(t1.amount),sum(t2.qty) ...
        // sum/avg全部数字型字段 count(1) countDistinct全部字段
        List<MultiConstant.MultiAggregateTypeEnum> aggregateAllTypes = this.wrapperMain.getAggregateAllTypes();
        List<String> aggregateFieldAll = new ArrayList<>(32);
        if (aggregateAllTypes.size() > 0) {
            aggregateFieldAll.addAll(
                    aggregateAllTypes.stream().flatMap(aggregateAllType ->
                            MultiConstant.MultiAggregateTypeEnum.COUNT.equals(aggregateAllType) ? Stream.of("count(DISTINCT " + mainTableName + ".id) as 'COUNT. . '") :
                                    Stream.of(
                                            this.computeAggregateFieldAssOne(this.wrapperMain, mainTableName, aggregateAllType),
                                            this.wrapperSubAndRelations.stream().flatMap(
                                                    multiWrapperSubAndRelation -> this.computeAggregateFieldAssOne(multiWrapperSubAndRelation.getWrapperSub(), multiWrapperSubAndRelation.getRelationCode(), aggregateAllType)
                                            )
                                    ).flatMap(l -> l)
                    ).collect(Collectors.toList())
            );
            System.out.println("aggregateFieldAss" + JSONUtil.toString(aggregateFieldAll));
            //过滤掉非数字的属性
        }
        if (MultiUtil.isEmpty(aggregateFieldAll)) {
            throw new MultiException("没有可以(要)聚合的列,无法查询");
        }

        //指定字段聚合
        String sqlSelect = "select " + String.join(",\n", aggregateFieldAll);
        String sqlFromLimit = "\nfrom " + mainTableName;
        String sqlLeftJoinOn = "\n" + String.join("\n", getSqlJoin(this.relationTree));
        String sqlWhere = this.getSqlWhere();

        return sqlSelect + sqlFromLimit + sqlLeftJoinOn + sqlWhere;
    }


    /**
     * 输出最终sql
     */
    public String computeSql() {
        String mainTableName = wrapperMain.getTableName();
        if (mainTableName == null) {
            throw new MultiException("请先通过MultiWrapperMain.lambda(UserInfo.class)或者.eq(UserInfo::getId)确定表名,在执行查询");
        }

        // 1. 解析 关系
        this.loadRelations();

        // 2.1. 解析 select要查出的字段语句片段
        // select user_staff.* from user_staff
        List<String> selectPropsList = this.wrapperSubAndRelations.stream().map(o -> o.getWrapperSub().getSqlSelectProps(o.getRelationCode())).collect(Collectors.toList());
        selectPropsList.add(0, wrapperMain.getSqlSelectProps(wrapperMain.getTableName()));
        String sqlSelect = "\nselect\n" + selectPropsList.stream().filter(Objects::nonNull).collect(Collectors.joining(",\n"));

        // 3. 解析 from主表,limit主表语句片段
        //	SELECT u.*,p.* FROM user_info                          u LEFT JOIN principal_user p ON p.user_id = u.id where p.admin_flag = 1;
        //	SELECT u.*,p.* FROM (select * from user_info limit 10) u LEFT JOIN principal_user p ON p.user_id = u.id where p.admin_flag = 1;
        String sqlFromLimit = "\nFROM " + wrapperMain.getSqlFromLimit(mainTableName);

        String sqlLeftJoinOn = "\n" + String.join("\n", getSqlJoin(this.relationTree));
//        String sqlLeftJoinOn = "\n" + this.wrapperSubAndRelations.stream().map(r -> r.getSqlJoin(mainTableName)).collect(Collectors.joining("\n"));

        String sqlWhere = this.getSqlWhere();

        return sqlSelect + sqlFromLimit + sqlLeftJoinOn + sqlWhere;
    }


    private Stream<String> computeAggregateFieldAssOne(MultiWrapperSelect<?, ?> multiWrapperSelect, String relationCode, MultiConstant.MultiAggregateTypeEnum aggregateAllType) {
        return multiWrapperSelect.getSelectFieldNames().stream()
                .filter(fieldName -> {
                            //过滤部分字段
                            Class<?> relation_fieldType = MultiRelationCaches.getRelation_fieldType(fieldName, multiWrapperSelect.getClazz());
                            return aggregateAllType.getFieldTypeFilter().apply(relation_fieldType);
                        }
                ).map(fieldName -> {
                    String aggregate = aggregateAllType.name() + "(" + relationCode + "." + fieldName + ")";
                    if (MultiConstant.MultiAggregateTypeEnum.SUM.equals(aggregateAllType)) {
                        aggregate = "IFNULL(" + aggregate + ", 0)";
                    }
                    return aggregate + " as '" + aggregateAllType.name() + "." + relationCode + "." + fieldName + "'";
                });
    }


    private List<String> getSqlJoin(MultiTreeNode<IMultiWrapperSubAndRelationTreeNode> relationTree) {
        String relationCode = relationTree.getCurr().getRelationCode();
        List<String> leftJoins = relationTree.getChildren().stream().map(r -> {
            if (r.getCurr() instanceof MultiWrapperSubAndRelation) {
                //noinspection rawtypes
                MultiWrapperSubAndRelation curr = (MultiWrapperSubAndRelation) r.getCurr();
                String subRelationCode = curr.getRelationCode();
                MultiTableRelation relation = MultiTableRelationFactory.INSTANCE.getRelationCodeMap().get(subRelationCode);
                boolean thisIs1 = curr.getTableNameThis().equals(relation.getTableName1());
                String leftJsonOn = " left join " + curr.getTableNameThis() + " " + subRelationCode + " on "
                        + subRelationCode + "." + (thisIs1 ? relation.getClass1KeyProp() : relation.getClass2KeyProp())
                        + "="
                        + relationCode + "." + (thisIs1 ? relation.getClass2KeyProp() : relation.getClass1KeyProp());
                String sqlWhereSub = curr.getWrapperSub().getSqlWhereProps(curr.getRelationCode());
                return leftJsonOn + (MultiUtil.isEmpty(sqlWhereSub) ? "" : " and " + sqlWhereSub);
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());
        List<String> leftJoinsSub = relationTree.getChildren().stream().flatMap(child -> getSqlJoin(child).stream()).collect(Collectors.toList());
        leftJoins.addAll(leftJoinsSub);
        return leftJoins;
    }

    private String getSqlWhere() {
        // 4. 解析 where条件语句片段
        //    where user_staff.state = 0
        //      and user_staff_address.del_flag = 0
        List<String> sqlWheres = new ArrayList<>();

        String sqlWhereMain = this.wrapperMain.getSqlWhereProps(wrapperMain.getRelationCode());
        List<String> sqlWhereSubMain = this.getSqlMainWhMerePropsMainRecursion(this.relationTree);
        sqlWheres.add(sqlWhereMain);
        sqlWheres.addAll(sqlWhereSubMain);

        String sqlWhereAppend = String.join("\n and ", sqlWheres);
        return MultiUtil.isEmpty(sqlWhereAppend) ? MultiConstant.Strings.EMPTY : "\nwhere 1=1\n  and" + sqlWhereAppend;
    }

    private List<String> getSqlMainWhMerePropsMainRecursion(MultiTreeNode<IMultiWrapperSubAndRelationTreeNode> treeNode) {
        List<String> sqlChildren = treeNode.getChildren().stream().map(treeNodeChild -> {
            IMultiWrapperSubAndRelationTreeNode curr = treeNodeChild.getCurr();
            if (curr instanceof MultiWrapperSubAndRelation) {
                MultiWrapperSubMainWhere<?> mainWhere = ((MultiWrapperSubAndRelation<?>) curr).getWrapperSub().getMainWhere();
                if (mainWhere != null) {
                    return mainWhere.getSqlWhereProps(curr.getRelationCode());
                }
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());
        List<String> sqlGrandChildren = treeNode.getChildren().stream().flatMap(treeNodeChild -> getSqlMainWhMerePropsMainRecursion(treeNodeChild).stream()).filter(Objects::nonNull).collect(Collectors.toList());
        sqlChildren.addAll(sqlGrandChildren);
        return sqlChildren;
    }

    private void loadRelations() {
        if (relationTree == null) {
            // 1.1 解析 主表和副表的关系树
            relationTree = this.reloadRelations(wrapperMain, this.wrapperSubAndRelations);

            // 1.2 关系统一按树自顶向下排列
            this.wrapperSubAndRelations = new ArrayList<>();
            relationTree.consumerTopToBottom(relationNode -> {
                if (relationNode instanceof MultiWrapperSubAndRelation) {
                    wrapperSubAndRelations.add((MultiWrapperSubAndRelation<?>) relationNode);
                }
            });
        }
    }

    private MultiTreeNode<IMultiWrapperSubAndRelationTreeNode> reloadRelations(MultiWrapperMain<MAIN> wrapperMain, List<MultiWrapperSubAndRelation<?>> wrapperSubAndRelations) {
        //relationCode可能缺省,去关系表中加载
        this.fillRelationCodeAndTableThisOther(wrapperMain, wrapperSubAndRelations);

        List<IMultiWrapperSubAndRelationTreeNode> relationsAndMain = new ArrayList<>(wrapperSubAndRelations);
        relationsAndMain.add(wrapperMain);

        //构建关系树  将用在
        // 1.在按顺序生成left join语句(否则会报错)
        // 2.按顺序映射查询结果到实体类上
        return MultiTreeNode.buildTree(relationsAndMain, o -> o, o -> o, o -> o instanceof MultiWrapperMain);
    }

    private void fillRelationCodeAndTableThisOther(MultiWrapperMain<MAIN> wrapperMain, List<MultiWrapperSubAndRelation<?>> wrapperSubAndRelations) {
        String mainTableName = wrapperMain.getTableName();

        List<MultiWrapperSubAndRelation<?>> hasCodeRelations = wrapperSubAndRelations.stream().filter(relation -> null != relation.getRelationCode()).collect(Collectors.toList());
        List<MultiWrapperSubAndRelation<?>> noCodeRelations = wrapperSubAndRelations.stream().filter(relation -> null == relation.getRelationCode()).collect(Collectors.toList());

        //已经确定关系的表
        Set<String> relationTableNames = new HashSet<>();
        relationTableNames.add(mainTableName);
        hasCodeRelations.forEach(relationHasCode -> {
            String subTableName = relationHasCode.getWrapperSub().getTableName();
            relationTableNames.add(relationHasCode.getWrapperSub().getTableName());
            MultiTableRelation relation = MultiTableRelationFactory.INSTANCE.getRelationCodeMap().get(relationHasCode.getRelationCode());
            this.fillTableThisAndOther(relationHasCode, subTableName, relation);
        });

        noCodeRelations.forEach(noCodeRelation ->
                {
                    String subTableName = noCodeRelation.getWrapperSub().getTableName();
                    boolean hasRelation = false;
                    for (String relationTableName1 : relationTableNames) {
                        List<MultiTableRelation> relations = MultiTableRelationFactory.INSTANCE.getRelation2TableNameMap()
                                .getOrDefault(subTableName, Collections.emptyMap())
                                .getOrDefault(relationTableName1, Collections.emptyList());
                        if (relations.size() > 1) {
                            //有多种关系,需要重新确定
                            throw new MultiException(relationTableName1 + "和" + subTableName + "存在多种关系,需要手动指定relationCode");
                        }
                        if (relations.size() < 1) {
                            //有多种关系,需要重新确定
                            throw new MultiException(relationTableName1 + "和" + subTableName + "没有存在表关系,无法关联");
                        }
                        MultiTableRelation relation = relations.get(0);

                        noCodeRelation.setRelationCode(relation.getCode());
                        this.fillTableThisAndOther(noCodeRelation, subTableName, relation);

                        hasRelation = true;
                        break;
                    }
                    if (hasRelation) {
                        relationTableNames.add(subTableName);
                    } else {
                        throw new MultiException(subTableName + "和[" + String.join(",", relationTableNames) + "]没有存在表关系,无法关联");
                    }
                }
        );

        // AggregateInfos 聚合信息中填充relationCode
        wrapperMain.getMultiAggregateInfos().forEach(a -> a.setRelationCode(mainTableName));
        //todo 初始化relationCode
//        wrapperSubAndRelations.forEach(reloadRelation -> reloadRelation.getWrapperSub().getMultiAggregateInfos().forEach(a -> a.setRelationCode(reloadRelation.getRelationCode())));
    }

    private void fillTableThisAndOther(MultiWrapperSubAndRelation<?> noCodeRelation, String subTableName, MultiTableRelation multiTableRelation) {
        String tableName1 = multiTableRelation.getTableName1();
        String tableName2 = multiTableRelation.getTableName2();
        if (tableName1.equals(subTableName)) {
            noCodeRelation.setTableNameThis(tableName1);
            noCodeRelation.setTableNameThisOneOrMany(multiTableRelation.getClass1OneOrMany());
            noCodeRelation.setTableNameOtherRequire(multiTableRelation.getClass1Require());
            noCodeRelation.setTableNameOther(tableName2);
        } else if (tableName2.equals(subTableName)) {
            noCodeRelation.setTableNameThis(tableName2);
            noCodeRelation.setTableNameThisOneOrMany(multiTableRelation.getClass2OneOrMany());
            noCodeRelation.setTableNameOtherRequire(multiTableRelation.getClass2Require());
            noCodeRelation.setTableNameOther(tableName1);
        } else {
            throw new MultiException("表关系" + multiTableRelation.getCode() + "(" + tableName1 + "," + tableName2 + ")其中之一必须和当前查询的表" + subTableName);
        }
    }

    /**
     *
     */
    private void addToListNew(List<MultiWrapperSubAndRelation<?>> wrapperSubAndRelationsNew, Set<String> parentTableNames, List<MultiWrapperSubAndRelation<?>> wrapperSubAndRelations) {
        ArrayList<MultiWrapperSubAndRelation<?>> multiWrapperSubAndRelationsTemp = new ArrayList<>(wrapperSubAndRelations);

        List<MultiWrapperSubAndRelation<?>> subRelations = wrapperSubAndRelations.stream().filter(relation ->
                {
                    MultiTableRelation relation1Now = getRelationByCode(relation.getRelationCode());
                    return parentTableNames.stream().anyMatch(parentTable -> relation1Now.getTableNames().contains(parentTable));
                }
        ).collect(Collectors.toList());
        wrapperSubAndRelationsNew.addAll(subRelations);
        multiWrapperSubAndRelationsTemp.removeAll(subRelations);

        //添加父节点表名
        subRelations.forEach(subRelation -> parentTableNames.addAll(getRelationByCode(subRelation.getRelationCode()).getTableNames()));

        if (multiWrapperSubAndRelationsTemp.size() > 0) {
            //递归添加
            addToListNew(wrapperSubAndRelationsNew, parentTableNames, multiWrapperSubAndRelationsTemp);
        }
    }

    private MultiTableRelation getRelationByCode(String relationCode) {
        return MultiTableRelationFactory.INSTANCE.getRelationCodeMap().get(relationCode);
    }

    public List<MAIN> list() {
        return Collections.emptyList();
    }
}
