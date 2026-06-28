package com.zhh.handsome.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import java.lang.ref.PhantomReference;

public class MongoDBConfig {
    private static final String CONNECTION_STRING = "mongodb://localhost:27017";

    private static final String DATABASE_NAME = "article";

    public static MongoClient getMongoClient() {
        return MongoClients.create(CONNECTION_STRING);
    }

    public static MongoDatabase getDatabase() {
        return getMongoClient().getDatabase(DATABASE_NAME);
    }
}
