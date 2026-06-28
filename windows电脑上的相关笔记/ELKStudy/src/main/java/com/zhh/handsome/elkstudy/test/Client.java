package com.zhh.handsome.elkstudy.test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.io.IOException;


public class Client {
    public static void main(String[] args) throws IOException {
        //创建ES客户端
        /*RestHighLevelClient esClient = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost("localhost", 9200, "http")
                )
        );*/
        //创建索引
        /*CreateIndexResponse user1 = esClient.indices().create(new CreateIndexRequest("user"), RequestOptions.DEFAULT);
        boolean acknowledged = user1.isAcknowledged();
        System.out.println("acknowledged = " + acknowledged);*/
        /*GetIndexRequest request = new GetIndexRequest("user");
        GetIndexResponse getIndexResponse = esClient.indices().get(request, RequestOptions.DEFAULT);
        System.out.println(getIndexResponse.getAliases());
        System.out.println(getIndexResponse.getMappings());
        System.out.println(getIndexResponse.getSettings());
        System.out.println(getIndexResponse.getDataStreams());*/
       /* DeleteIndexRequest request = new DeleteIndexRequest("user");
        AcknowledgedResponse delete = esClient.indices().delete(request, RequestOptions.DEFAULT);
        System.out.println(delete.isAcknowledged());*/
        // 创建 Elasticsearch 8.x 客户端
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200)
        ).build();

        // 创建传输层
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        // 创建 API 客户端
        ElasticsearchClient esClient = new ElasticsearchClient(transport);

        /*try {
            // 创建用户对象
            User user = new User();
            user.setName("张三");
            user.setAge(18);
            user.setSex("男");

            //往user索引里面添加数据
            IndexRequest<User> indexRequest = new IndexRequest.Builder< User>()
                    .index("user")
                    .id("1001")
                    .document(user)
                    .build();
            IndexResponse index = esClient.index(indexRequest);
            System.out.println(index.result());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 关闭客户端
            transport.close();
            restClient.close();
        }
*/
        GetResponse<User> user = esClient.get(new GetRequest.Builder().index("user").id("1001").build(), User.class);
        System.out.println(user.source());

        DeleteResponse user1 = esClient.delete(new DeleteRequest.Builder().index("user").id("1001").build());
        System.out.println(user1.result());


        transport.close();
        restClient.close();
    }
}
