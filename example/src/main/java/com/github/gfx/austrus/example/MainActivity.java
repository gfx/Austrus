package com.github.gfx.austrus.example;

import com.github.gfx.austrus.example.databinding.ActivityMainBinding;
import com.github.gfx.austrus.example.databinding.ItemBinding;

import android.content.Context;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    static final String TAG = MainActivity.class.getSimpleName();

    ActivityMainBinding binding;

    ItemAdapter adapter;

    class ItemAdapter extends ArrayAdapter<HttpResponse> {

        public ItemAdapter(Context context) {
            super(context, 0);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = ItemBinding.inflate(LayoutInflater.from(getContext()), parent, false)
                        .getRoot();
            }

            ItemBinding binding = DataBindingUtil.getBinding(convertView);
            HttpResponse response = getItem(position);
            binding.uri.setText(response.uri.toString());
            binding.iamge.setImageBitmap(response.getBodyAsBitmap());

            return convertView;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        adapter = new ItemAdapter(this);
        binding.list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                new AsyncTask<Uri, Void, Map<Uri, HttpResponse>>() {
                    @Override
                    protected Map<Uri, HttpResponse> doInBackground(Uri... uris) {
                        long t0;
                        // serial requests
                        t0 = System.currentTimeMillis();
                        try {
                            for (Uri uri : uris) {
                                request(uri);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            Log.d(TAG, "serial: " + (System.currentTimeMillis() - t0) + "ms");
                        }

                        // parallel requests
                        t0 = System.currentTimeMillis();
                        try {
                            return request(uris);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            Log.d(TAG, "multiplexing: " + (System.currentTimeMillis() - t0) + "ms");
                        }
                    }

                    @Override
                    protected void onPostExecute(Map<Uri, HttpResponse> map) {
                        for (Map.Entry<Uri, HttpResponse> entry : map.entrySet()) {
                            Log.d("Response", entry.getKey().toString());
                            final HttpResponse response = entry.getValue();

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    adapter.add(response);
                                }
                            });
                        }
                    }
                }.execute(
                        Uri.parse("http://developer.android.com/images/cards/android-wear_2x.png"),
                        Uri.parse("http://developer.android.com/images/cards/android-tv_2x.png"),
                        Uri.parse("http://developer.android.com/images/cards/android-auto_2x.png"),
                        Uri.parse(
                                "http://developer.android.com/design/media/hero-material-design.png"),
                        Uri.parse(
                                "http://www.android.com/static/img/history/features/feature_marshmallow_1.png")
                );
            }
        }, 2000);
    }

    static class HttpResponse {

        final Uri uri;

        final byte[] rawResponse;

        int beginBody;

        HttpResponse(Uri uri, byte[] rawResponse) {
            this.uri = uri;
            this.rawResponse = rawResponse;

            for (int i = 0; i < rawResponse.length; i++) {
                if (rawResponse[i] == '\r') {
                    i++;
                    if (i < rawResponse.length) {
                        if (rawResponse[i] == '\n') {
                            i++;
                            if (i < rawResponse.length) {
                                if (rawResponse[i] == '\r') {
                                    i++;
                                    if (i < rawResponse.length) {
                                        if (rawResponse[i] == '\n') {
                                            beginBody = i + 1;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "headers: " + new String(rawResponse, 0, beginBody)
                    .replaceAll("\r\n", " "));

        }

        Bitmap getBodyAsBitmap() {
            return BitmapFactory
                    .decodeByteArray(rawResponse, beginBody, rawResponse.length - beginBody);
        }
    }

    static class HttpRequest {

        final Uri uri;

        final ByteBuffer byteBuffer;

        final ByteArrayOutputStream rawResponse;

        HttpRequest(Uri uri) {
            this.uri = uri;
            byteBuffer = toByteBuffer(createRequest(uri.getHost(), uri.getPath()));
            rawResponse = new ByteArrayOutputStream(1024);
        }

        ByteBuffer getRequestBuffer() {
            return byteBuffer;
        }

        ByteArrayOutputStream getRawResponse() {
            return rawResponse;
        }

        static ByteBuffer toByteBuffer(String s) {
            return ByteBuffer.wrap(s.getBytes(Charset.forName("UTF-8")));
        }

        static String createRequest(String host, String path) {
            String crlf = "\r\n";
            return "GET " + path + " HTTP/1.0" + crlf
                    + "Host: " + host + crlf
                    + "User-Agent: HttpTest/1.0" + crlf
                    + crlf;
        }

    }

    public Map<Uri, HttpResponse> request(Uri... uris) throws IOException {
        int size = 4096;

        Selector selector = Selector.open();

        Map<SocketChannel, HttpRequest> requests = new HashMap<>();

        // connects
        for (Uri uri : uris) {
            int port = uri.getPort();
            if (port == -1) {
                port = 80;
            }

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(uri.getHost(), port));
            channel.register(selector, SelectionKey.OP_CONNECT);

            requests.put(channel, new HttpRequest(uri));
        }

        // Sends requests and receives responses
        while (!selector.keys().isEmpty()) {
            if (selector.select(100) == 0) {
                continue;
            }

            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();

                if (key.isConnectable()) {
                    SocketChannel channel = (SocketChannel) key.channel();
                    if (!channel.finishConnect()) {
                        throw new IOException("Connection Failed");
                    }
                    channel.register(selector, SelectionKey.OP_WRITE);
                }

                if (key.isWritable()) {
                    SocketChannel channel = (SocketChannel) key.channel();
                    HttpRequest request = requests.get(channel);
                    ByteBuffer buffer = request.getRequestBuffer();
                    int n = channel.write(buffer);
                    if (!buffer.hasRemaining()) {
                        ByteBuffer readBuf = ByteBuffer.allocate(size);
                        channel.register(selector, SelectionKey.OP_READ, readBuf);
                    }
                }

                if (key.isReadable()) {
                    ByteBuffer buffer = (ByteBuffer) key.attachment();
                    SocketChannel channel = (SocketChannel) key.channel();
                    int n = channel.read(buffer);
                    //Log.d("Read", "n=" + n);
                    if (n > 0) {
                        HttpRequest request = requests.get(channel);
                        request.getRawResponse().write(buffer.array(), 0, buffer.position());
                        buffer.clear();
                    } else if (n < 0) {
                        channel.close();
                    }
                }

                iterator.remove();
            }
        }

        selector.close();

        Map<Uri, HttpResponse> result = new HashMap<>();
        for (HttpRequest request : requests.values()) {
            result.put(request.uri,
                    new HttpResponse(request.uri, request.getRawResponse().toByteArray()));
        }
        return result;
    }
}
