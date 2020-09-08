package com.niewj.suv.service.impl;

import com.niewj.Car;

/**
 * Created by niewj on 2020/9/8 14:21
 */
public class SuvCar implements Car {
    @Override
    public void drive() {
        System.out.println("========================");
        System.out.println("=====I driving a SUV Car, dududududu, 翻山越岭，yeyeye=====");
        System.out.println("========================");
    }
}
