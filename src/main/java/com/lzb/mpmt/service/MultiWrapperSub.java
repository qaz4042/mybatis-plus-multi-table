package com.lzb.mpmt.service;

import com.lzb.mpmt.service.common.WhereTreeNode;
import com.lzb.mpmt.service.intf.MultiWrapperLimit;
import com.lzb.mpmt.service.intf.MultiWrapperSelect;
import com.lzb.mpmt.service.intf.MultiWrapperWhere;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@SuppressWarnings("unused")
public class MultiWrapperSub<MAIN> implements
        MultiWrapperWhere<MAIN, MultiWrapperSub<MAIN>>,
        MultiWrapperSelect<MAIN, MultiWrapperSub<MAIN>>
{

    //下划线表名
    private String tableName;

    // where id='1' and name like '%张三%   '多个条件 n1 and ( n2 or n3 )
    private WhereTreeNode whereTree = new WhereTreeNode();

    // select id,name,sex
    private List<String> selectProps;


    public static <MAIN> MultiWrapperSub<MAIN> lambda(Class<MAIN> clazz) {
        return new MultiWrapperSub<>();
    }
}
