package org.example.springai1.FFmepg;

import ws.schild.jave.*;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.encode.VideoAttributes;
import ws.schild.jave.info.MultimediaInfo;
import ws.schild.jave.info.VideoSize;
import ws.schild.jave.progress.EncoderProgressListener;

import java.io.File;

/**
 * FFmpeg JAVE库演示类
 * 演示如何使用JAVE（Java Audio Video Encoder）库进行音视频处理
 */
public class BagDemo {
    //这个库看着很有意思，回来学一学


    /**
     * 获取媒体文件信息
     * @param sourceFilePath 源文件路径
     */
    public void getMediaInfo(String sourceFilePath) {
        try {
            File source = new File(sourceFilePath);
            MultimediaObject multimediaObject = new MultimediaObject(source);
            MultimediaInfo info = multimediaObject.getInfo();
            
            System.out.println("文件格式: " + info.getFormat());
            System.out.println("时长(毫秒): " + info.getDuration());
            
            // 音频信息
            if (info.getAudio() != null) {
                System.out.println("音频编码: " + info.getAudio().getDecoder());
                System.out.println("声道数: " + info.getAudio().getChannels());
                System.out.println("采样率: " + info.getAudio().getSamplingRate());
                System.out.println("比特率: " + info.getAudio().getBitRate());
            }
            
            // 视频信息
            if (info.getVideo() != null) {
                System.out.println("视频编码: " + info.getVideo().getDecoder());
                System.out.println("帧率: " + info.getVideo().getFrameRate());
                System.out.println("宽度: " + info.getVideo().getSize().getWidth());
                System.out.println("高度: " + info.getVideo().getSize().getHeight());
                System.out.println("比特率: " + info.getVideo().getBitRate());
            }
        } catch (Exception e) {
            System.err.println("获取媒体信息时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 视频转码
     * @param sourceFilePath 源文件路径
     * @param targetFilePath 目标文件路径
     */
    public void convertVideo(String sourceFilePath, String targetFilePath) {
        try {
            File source = new File(sourceFilePath);
            File target = new File(targetFilePath);

            // 音频属性设置
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("aac"); // 使用AAC编码
            audio.setBitRate(128000); // 128kbps
            audio.setChannels(2); // 立体声
            audio.setSamplingRate(44100); // 44.1kHz

            // 视频属性设置
            VideoAttributes video = new VideoAttributes();
            video.setCodec("h264"); // 使用H.264编码
            video.setBitRate(1600000); // 1.6Mbps
            video.setFrameRate(30); // 30fps
            video.setSize(new VideoSize(1280, 720)); // 720p

            // 编码属性设置
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("mp4"); // 输出格式为MP4
            attrs.setAudioAttributes(audio);
            attrs.setVideoAttributes(video);

            // 进度监听器
            EncoderProgressListener listener = new EncoderProgressListener() {
                @Override
                public void sourceInfo(MultimediaInfo info) {
                    System.out.println("开始处理文件: " + sourceFilePath);
                }

                @Override
                public void progress(int permil) {
                    double percentage = permil / 10.0;
                    System.out.println("处理进度: " + String.format("%.1f", percentage) + "%");
                }

                @Override
                public void message(String message) {
                    System.out.println("处理消息: " + message);
                }
            };

            // 执行转码
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(source), target, attrs, listener);
            System.out.println("视频转码完成: " + targetFilePath);
        } catch (Exception e) {
            System.err.println("视频转码时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 提取音频
     * @param sourceFilePath 源文件路径
     * @param targetAudioPath 目标音频路径
     */
    public void extractAudio(String sourceFilePath, String targetAudioPath) {
        try {
            File source = new File(sourceFilePath);
            File target = new File(targetAudioPath);

            // 音频属性设置
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("mp3"); // 输出为MP3格式
            audio.setBitRate(128000); // 128kbps
            audio.setChannels(2); // 立体声
            audio.setSamplingRate(44100); // 44.1kHz

            // 编码属性设置（仅音频）
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("mp3"); // 输出格式为MP3
            attrs.setAudioAttributes(audio);
            attrs.setVideoAttributes(null); // 不包含视频

            // 执行音频提取
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(source), target, attrs);
            System.out.println("音频提取完成: " + targetAudioPath);
        } catch (Exception e) {
            System.err.println("提取音频时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 视频截图
     * @param sourceFilePath 源文件路径
     * @param targetImagePath 目标图片路径
     * @param position 截图位置（毫秒）
     */
    public void takeScreenshot(String sourceFilePath, String targetImagePath, int position) {
        try {
            File source = new File(sourceFilePath);
            File target = new File(targetImagePath);

            // 视频属性设置
            VideoAttributes video = new VideoAttributes();
            video.setCodec("png"); // 输出为PNG格式
            video.setSize(new VideoSize(1280, 720)); // 设置截图尺寸

            // 编码属性设置
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("image2"); // 图片输出格式
            attrs.setVideoAttributes(video);
            attrs.setDuration(0.01F); // 只处理很短的时间
            attrs.setOffset(Float.valueOf(position / 1000.0f)); // 设置偏移时间（秒）

            // 执行截图
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(source), target, attrs);
            System.out.println("视频截图完成: " + targetImagePath);
        } catch (Exception e) {
            System.err.println("视频截图时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 视频剪辑
     * @param sourceFilePath 源文件路径
     * @param targetFilePath 目标文件路径
     * @param start 开始时间（毫秒）
     * @param duration 持续时间（毫秒）
     */
    public void clipVideo(String sourceFilePath, String targetFilePath, int start, int duration) {
        try {
            File source = new File(sourceFilePath);
            File target = new File(targetFilePath);

            // 音频属性设置
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("aac");

            // 视频属性设置
            VideoAttributes video = new VideoAttributes();
            video.setCodec("h264");

            // 编码属性设置
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("mp4");
            attrs.setAudioAttributes(audio);
            attrs.setVideoAttributes(video);
            attrs.setOffset(Float.valueOf(start / 1000.0f)); // 开始时间（秒）
            attrs.setDuration(Float.valueOf(duration / 1000.0f)); // 持续时间（秒）

            // 执行剪辑
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(source), target, attrs);
            System.out.println("视频剪辑完成: " + targetFilePath);
        } catch (Exception e) {
            System.err.println("视频剪辑时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 演示方法
     */
    public static void main(String[] args) {
        BagDemo demo = new BagDemo();
        
        // 示例用法（需要根据实际情况修改文件路径）
        /*
        // 获取媒体信息
        demo.getMediaInfo("input.mp4");
        
        // 视频转码
        demo.convertVideo("input.mp4", "output.mp4");
        
        // 提取音频
        demo.extractAudio("input.mp4", "audio.mp3");
        
        // 视频截图（在第10秒处截图）
        demo.takeScreenshot("input.mp4", "screenshot.png", 10000);
        
        // 视频剪辑（从第30秒开始，持续10秒）
        demo.clipVideo("input.mp4", "clip.mp4", 30000, 10000);
        */
        
        System.out.println("FFmpeg JAVE库演示");
        System.out.println("请根据实际需求修改文件路径并取消注释相关代码来测试功能");
    }
}