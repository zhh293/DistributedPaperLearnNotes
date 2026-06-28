package WebFlux.entity;

/*
import java.util.*;

public class numbers {
    public int maximumDifference(int[] nums) {
        //我的思路是把下标和对应的值绑定在一起，为值排序的时候把下标也带上
        List<node>list=new ArrayList<>();
        for(int i=0;i<nums.length;i++){
            node node=new node(nums[i],i);
            list.add(node);
        }
        Collections.sort(list, new Comparator<node>() {
            @Override
            public int compare(node o1, node o2) {
                return o1.val-o2.val;
            }
        });
        int count=0;
        for(int i=list.size()-1;i>0;i--){
            if(list.get(i).getVal()-list.get(0).getVal()>=0&&list.get(i).getIndex()>=list.get(0).getIndex()){
                return list.get(i).getVal()-list.get(0).getVal();
            }
        }
        return -1;

    }


}
class node {
    int val;
    int index;
    node next;
    node() {}
    node(int val, int index){
        this.val = val;
        this.index = index;
    }
    public int getIndex() {
        return index;
    }
    public int getVal() {
        return val;
    }
    public void setVal(int val) {
        this.val = val;
    }
    public void setIndex(int index) {
        this.index = index;
    }
}
*/
