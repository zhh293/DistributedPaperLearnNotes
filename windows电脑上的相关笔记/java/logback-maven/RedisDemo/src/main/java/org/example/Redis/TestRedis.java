package org.example.Redis;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestRedis {
    public static void main(String[] args) throws Exception {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
        buffer.writeBytes("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n*2\r\n$3\r\nget\r\n$3\r\nkey\r\n".getBytes());
        RespFrameDecoder respFrameDecoder = new RespFrameDecoder();
        List<Object>list=new ArrayList<>();
        respFrameDecoder.decode(null, buffer, list);
        System.out.println(buffer.readerIndex());
        System.out.println(Arrays.toString(list.toArray()));
    }
}
