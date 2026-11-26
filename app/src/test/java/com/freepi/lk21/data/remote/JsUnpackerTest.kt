package com.freepi.lk21.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test

class JsUnpackerTest {

    @Test
    fun `unpacks packer payload`() {
        // A valid Dean Edwards packer payload. Words 0..6:
        // sources|https|cdn|example|com|movie|m3u8
        val packed = "eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(parseInt(c/a)))+" +
            "((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String))" +
            "{while(c--){d[e(c)]=k[c]||e(c)}k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};" +
            "while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}return p}" +
            "('0=[{file:\"1://2.3.4/5.6\"}]',62,7,'sources|https|cdn|example|com|movie|m3u8'.split('|'),0,{}))"

        val outputs = JsUnpacker.unpackAll(packed)
        assertTrue(outputs.isNotEmpty())
        assertTrue(
            "Packed source URL should be recovered, got: $outputs",
            outputs.any { it.contains("https://cdn.example.com/movie.m3u8") }
        )
    }
}
