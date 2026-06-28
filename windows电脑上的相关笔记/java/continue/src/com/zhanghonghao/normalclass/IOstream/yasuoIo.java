package com.zhanghonghao.normalclass.IOstream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class yasuoIo {
    public static void main(String[] args) {
        //压缩流操作对象是zip压缩包
        /*1. 压缩流（输出流）
类名	功能描述	对应解压缩流
ZipOutputStream	用于创建 ZIP 格式的压缩文件，支持多个文件 / 目录的压缩（多条目）	ZipInputStream
GzipOutputStream	用于创建 GZIP 格式的压缩文件，每次只能压缩单个文件（单条目，无目录结构）	GzipInputStream
DeflaterOutputStream	底层压缩流（基于 DEFLATE 算法），可自定义压缩级别，不包含头部 / 校验信息	InflaterInputStream
2. 关键概念
ZIP 格式：支持多文件打包压缩，每个文件对应一个ZipEntry条目，包含文件名、大小、时间等元数据。
GZIP 格式：单文件压缩，压缩后文件扩展名通常为.gz，不支持多文件打包。
DEFLATE 算法：ZIP 和 GZIP 的底层压缩算法，通过Deflater类控制压缩级别（如最佳速度、最佳压缩比、默认等）。


五、注意事项
资源关闭：
使用try-with-resources（自动关闭流），避免手动调用close()。
ZipOutputStream和GzipOutputStream会自动关闭底层流（如FileOutputStream）。
目录处理：
压缩目录时，需手动添加目录条目（ZipEntry entry = new ZipEntry("dir/"); entry.setDirectory(true);）。
性能优化：
使用缓冲区（如 1024 字节数组）批量读写，避免单次字节操作。
大文件压缩时建议使用多线程或分块处理。
异常处理：
可能抛出IOException（如文件不存在、权限不足），需合理捕获处理。*/
        File src=new File("D:\\aaa.zip");
        File dest=new File("D:\\");
    }
    public static void unzip(File src,File dest) throws FileNotFoundException {
        //解压的本质是把压缩包中的每一个文件或者文件夹读取出来，按照层级拷贝到目的地当中
        //创建一个解压缩流来读取压缩包中的数据
        ZipInputStream zipInputStream=new ZipInputStream(new FileInputStream(src), Charset.forName("UTF-8"));
        try{
            ZipEntry nextEntry = zipInputStream.getNextEntry();
            while (nextEntry != null) {
                if (nextEntry.isDirectory()) {
                    //对文件夹进行处理，在dest创建一个同样的文件夹
                    File f=new File(dest,nextEntry.toString());
                    f.mkdirs();
                    nextEntry = zipInputStream.getNextEntry();
                }else {
                    //对文件进行处理
                    int b;
                    FileOutputStream fos=new FileOutputStream(new File(dest,nextEntry.toString()));
                    while ((b = zipInputStream.read()) != -1) {
                           fos.write(b);
                    }
                    fos.close();
                    zipInputStream.closeEntry();
                }
            }
            zipInputStream.close();
        }catch (Exception e){
            e.printStackTrace();
        }

/*细节
* 关键规则
File(File parent, String child)的拼接逻辑
若child是绝对路径（如D:\\aaa.txt），则忽略parent参数，直接使用child作为结果路径。
若child是相对路径（如"aaa.txt"或"dir\\file.txt"），则拼接为parent + child。
Windows 路径特性
绝对路径以驱动器号（如D:）或根路径（如\\server\share）开头。
相对路径不以驱动器号开头（如"dir\\file.txt"）。
示例分析*/



    }
}
