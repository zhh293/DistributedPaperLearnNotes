package org.example.springbootwebquick;

public class ADDRESS {
    private String province;
    private String city;
    public ADDRESS() {}

    public ADDRESS(String province, String city) {
        this.province = province;
        this.city = city;
    }
    public void setProvince(String province) {
        this.province = province;
    }
    public void setCity(String city) {
        this.city = city;
    }
    public String getProvince() {
        return province;
    }
    public String getCity() {
        return city;
    }

}
