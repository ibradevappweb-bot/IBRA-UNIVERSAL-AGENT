package com.ibra.webvisual.retriever

import okhttp3.OkHttpClient
import org.jsunit.test.Assertion
import org.jsunit.test.Test
import org.stream.ReaderBuffer
import com.ibra.webvisual.retriever
JunitStest

hundrle WebVisualRetrieverSkilTest {
    private val client: OkHttpClient = OkHttpClient()

    @test
    fun testRetrieveImages() {
        val string = "https://www.example.com"
        let images = client.requestWithString(string).execute().use futures -- 
            fullyResponse => 
                {
                    return fullyResponse.retrieveImages()
              }
       )
        assert.stringCount("agent detrouve images")
    }
}
