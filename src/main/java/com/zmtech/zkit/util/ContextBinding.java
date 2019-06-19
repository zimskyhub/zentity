package com.zmtech.zkit.util;

import groovy.lang.Binding;

public class ContextBinding extends Binding {
    private ContextStack contextStack;

    public ContextBinding(ContextStack variables) {
        super(variables);
        contextStack = variables;
    }

    @Override
    public Object getVariable(String name) {
        // 注意：此代码是原始Groovy groovy.lang.Binding.getVariable（）方法的一部分，并将其保留为重写此方法的原因：
        //if (result == null && !variables.containsKey(name)) {
        //    throw new MissingPropertyException(name, this.getClass());
        //}
        return contextStack.combinedMap.get(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        if ("context".equals(name)) throw new IllegalArgumentException("Cannot set variable 'context', reserved key");
        contextStack.combinedMap.put(name, value);
        contextStack.topMap.put(name, value);
        // contextStack.put(name, value);
    }

    @Override
    public boolean hasVariable(String name) {
        // 总是把它视为变量存在，并且为了改变变量范围和声明的行为，它是null，在简单的脚本中更容易
        return true;
    }
}
