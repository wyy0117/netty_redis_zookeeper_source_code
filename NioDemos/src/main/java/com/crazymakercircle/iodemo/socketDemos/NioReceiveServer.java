package com.crazymakercircle.iodemo.socketDemos;

import com.crazymakercircle.NioDemoConfig;
import com.crazymakercircle.util.IOUtil;
import com.crazymakercircle.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


/**
 * 文件传输Server端
 * Created by 尼恩@ 疯创客圈
 */
public class NioReceiveServer {

    //接受文件路径
    private static final String RECEIVE_PATH = NioDemoConfig.SOCKET_RECEIVE_PATH;

    private Charset charset = Charset.forName("UTF-8");

    /**
     * 服务器端保存的客户端对象，对应一个客户端文件
     */
    static class Client {
        //文件名称
        String fileName;
        //长度
        long fileLength;

        //开始传输的时间
        long startTime;

        //客户端的地址
        InetSocketAddress remoteAddress;

        //输出的文件通道
        FileChannel outChannel;

        //接收长度
        long receiveLength;

        public boolean isFinished() {
            return receiveLength >= fileLength;
        }
    }

    private ByteBuffer buffer
            = ByteBuffer.allocate(NioDemoConfig.SERVER_BUFFER_SIZE);

    //使用Map保存每个客户端传输，当OP_READ通道可读时，根据channel找到对应的对象
    Map<SelectableChannel, Client> clientMap = new HashMap<SelectableChannel, Client>();


    public void startServer() throws IOException {
        // 1、获取Selector选择器
        Selector selector = Selector.open();

        // 2、获取通道
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        ServerSocket serverSocket = serverChannel.socket();

        // 3.设置为非阻塞
        serverChannel.configureBlocking(false);
        // 4、绑定连接
        InetSocketAddress address
                = new InetSocketAddress(NioDemoConfig.SOCKET_SERVER_PORT);
        serverSocket.bind(address);
        // 5、将通道注册到选择器上,并注册的IO事件为：“接收新连接”
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        Logger.tcfo("serverChannel is linstening...");
        // 6、轮询感兴趣的I/O就绪事件（选择键集合）
        while (selector.select() > 0) {
            if (null == selector.selectedKeys()) continue;
            // 7、获取选择键集合
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                // 8、获取单个的选择键，并处理
                SelectionKey key = it.next();
                if (null == key) continue;

                // 9、判断key是具体的什么事件，是否为新连接事件
                if (key.isAcceptable()) {
                    // 10、若接受的事件是“新连接”事件,就获取客户端新连接
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel socketChannel = server.accept();
                    if (socketChannel == null) continue;
                    // 11、客户端新连接，切换为非阻塞模式
                    socketChannel.configureBlocking(false);
                    // 12、将客户端新连接通道注册到selector选择器上
                    SelectionKey selectionKey =
                            socketChannel.register(selector, SelectionKey.OP_READ);
                    // 余下为业务处理
                    Client client = new Client();
                    client.remoteAddress
                            = (InetSocketAddress) socketChannel.getRemoteAddress();
                    clientMap.put(socketChannel, client);
                    Logger.debug(socketChannel.getRemoteAddress() + "连接成功...");

                } else if (key.isReadable()) {
                    processData(key);
                }
                // NIO的特点只会累加，已选择的键的集合不会删除
                // 如果不删除，下一次又会被select函数选中
                it.remove();
            }
        }
    }

    /**
     * 处理客户端传输过来的数据
     */
    private void processData(SelectionKey key) throws IOException {
        Client client = clientMap.get(key.channel());

        SocketChannel socketChannel = (SocketChannel) key.channel();
        int num = 0;
        try {
            buffer.clear();
            while ((num = socketChannel.read(buffer)) > 0) {
                buffer.flip();
                //客户端发送过来的，首先处理文件名
                if (null == client.fileName) {

                    //0 <= position <= limit <= capacity
                    //这里之前用的是buffer.capacity()
                    // capacity 是初始化话的内部数组元素数量 ，如Byte 数量
                    // 实际上这里应该是  buffer.limit()
                    //错误代码，如下
//                    if (buffer.capacity() < 4) {
//                        continue;
//                    }
                 //修改后的代码,这个bug，是社群小伙伴  nicole 发现的，小伙伴潜力无限呀
                    if (buffer.limit() < 4) {
                        continue;
                    }
                    int fileNameLen = buffer.getInt();
                    byte[] fileNameBytes = new byte[fileNameLen];
                    buffer.get(fileNameBytes);

                    // 文件名
                    String fileName = new String(fileNameBytes, charset);

                    File directory = new File(RECEIVE_PATH);
                    if (!directory.exists()) {
                        directory.mkdir();
                    }
                    Logger.info("NIO  传输目标dir：", directory);

                    client.fileName = fileName;
                    String fullName = directory.getAbsolutePath() + File.separatorChar + fileName;
                    Logger.info("NIO  传输目标文件：", fullName);

                    File file = new File(fullName.trim());

                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    FileChannel fileChannel = new FileOutputStream(file).getChannel();
                    client.outChannel = fileChannel;

                    if (buffer.limit() < 8) {
                        continue;
                    }
                    // 文件长度
                    long fileLength = buffer.getLong();
                    client.fileLength = fileLength;
                    client.startTime = System.currentTimeMillis();
                    Logger.debug("NIO  传输开始：");

                    client.receiveLength += buffer.capacity();
                    if (buffer.limit() > 0) {
                        // 写入文件
                        client.outChannel.write(buffer);
                    }
                    if (client.isFinished()) {
                        finished(key, client);
                    }
                }
                //客户端发送过来的，最后是文件内容
                else {
                    client.receiveLength += buffer.capacity();
                    // 写入文件
                    client.outChannel.write(buffer);
                    if (client.isFinished()) {
                        finished(key, client);
                    }
                }
                buffer.clear();
            }
            key.cancel();
        } catch (IOException e) {
            key.cancel();
            e.printStackTrace();
            return;
        }
        // 调用close为-1 到达末尾
        if (num == -1) {
            finished(key, client);
        }
    }

    private void finished(SelectionKey key, Client client) {
        IOUtil.closeQuietly(client.outChannel);
        Logger.info("上传完毕");
        key.cancel();
        Logger.debug("文件接收成功,File Name：" + client.fileName);
        Logger.debug(" Size：" + IOUtil.getFormatFileSize(client.fileLength));
        long endTime = System.currentTimeMillis();
        Logger.debug("NIO IO 传输毫秒数：" + (endTime - client.startTime));
    }


    /**
     * 入口
     *
     * @param args
     */
    public static void main(String[] args) throws Exception {
        NioReceiveServer server = new NioReceiveServer();
        server.startServer();
    }
}