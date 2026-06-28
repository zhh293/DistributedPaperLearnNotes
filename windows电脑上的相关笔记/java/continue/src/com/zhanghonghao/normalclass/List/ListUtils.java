package com.zhanghonghao.normalclass.List;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
//接口标明泛型的类型之后，影响的是接口所含有的方法的参数等等，对于特有的自己书写的方法是没有任何影响的，哈哈哈
public class ListUtils implements List<String> {
    private ListUtils() {
    }

    public static <T> void addAll(List<T> list, T t) {
        list.add(t);
    }

    //但这样添加元素的时候太不灵活，不能想添加几个就添加几个，所以引入可变参数
    public static <T> void addAll2(List<T> list, T... t) {
        for (T element : t) {
            list.add(element);
        }

    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator<String> iterator() {
        return null;
    }

    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return null;
    }

    @Override
    public boolean add(String s) {
        return false;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends String> c) {
        return false;
    }

    @Override
    public boolean addAll(int index, Collection<? extends String> c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public String get(int index) {
        return "";
    }

    @Override
    public String set(int index, String element) {
        return "";
    }

    @Override
    public void add(int index, String element) {

    }

    @Override
    public String remove(int index) {
        return "";
    }

    @Override
    public int indexOf(Object o) {
        return 0;
    }

    @Override
    public int lastIndexOf(Object o) {
        return 0;
    }

    @Override
    public ListIterator<String> listIterator() {
        return null;
    }

    @Override
    public ListIterator<String> listIterator(int index) {
        return null;
    }

    @Override
    public List<String> subList(int fromIndex, int toIndex) {
        return List.of();
    }
}