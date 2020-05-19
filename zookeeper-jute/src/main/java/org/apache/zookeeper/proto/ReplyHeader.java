// File generated by hadoop record compiler. Do not edit.
/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.zookeeper.proto;

import org.apache.jute.*;
import org.apache.yetus.audience.InterfaceAudience;

@InterfaceAudience.Public
public class ReplyHeader implements Record {
  private int xid;
  private long zxid;
  private int err;
  public ReplyHeader() {
  }
  public ReplyHeader(
        int xid,
        long zxid,
        int err) {
    this.xid=xid;
    this.zxid=zxid;
    this.err=err;
  }
  public int getXid() {
    return xid;
  }
  public void setXid(int m_) {
    xid=m_;
  }
  public long getZxid() {
    return zxid;
  }
  public void setZxid(long m_) {
    zxid=m_;
  }
  public int getErr() {
    return err;
  }
  public void setErr(int m_) {
    err=m_;
  }
  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(this,tag);
    a_.writeInt(xid,"xid");
    a_.writeLong(zxid,"zxid");
    a_.writeInt(err,"err");
    a_.endRecord(this,tag);
  }
  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(tag);
    xid=a_.readInt("xid");
    zxid=a_.readLong("zxid");
    err=a_.readInt("err");
    a_.endRecord(tag);
}
  public String toString() {
    try {
      java.io.ByteArrayOutputStream s =
        new java.io.ByteArrayOutputStream();
      CsvOutputArchive a_ =
        new CsvOutputArchive(s);
      a_.startRecord(this,"");
    a_.writeInt(xid,"xid");
    a_.writeLong(zxid,"zxid");
    a_.writeInt(err,"err");
      a_.endRecord(this,"");
      return new String(s.toByteArray(), "UTF-8");
    } catch (Throwable ex) {
      ex.printStackTrace();
    }
    return "ERROR";
  }
  public void write(java.io.DataOutput out) throws java.io.IOException {
    BinaryOutputArchive archive = new BinaryOutputArchive(out);
    serialize(archive, "");
  }
  public void readFields(java.io.DataInput in) throws java.io.IOException {
    BinaryInputArchive archive = new BinaryInputArchive(in);
    deserialize(archive, "");
  }
  public int compareTo (Object peer_) throws ClassCastException {
    if (!(peer_ instanceof ReplyHeader)) {
      throw new ClassCastException("Comparing different types of records.");
    }
    ReplyHeader peer = (ReplyHeader) peer_;
    int ret = 0;
    ret = (xid == peer.xid)? 0 :((xid<peer.xid)?-1:1);
    if (ret != 0) return ret;
    ret = (zxid == peer.zxid)? 0 :((zxid<peer.zxid)?-1:1);
    if (ret != 0) return ret;
    ret = (err == peer.err)? 0 :((err<peer.err)?-1:1);
    if (ret != 0) return ret;
     return ret;
  }
  public boolean equals(Object peer_) {
    if (!(peer_ instanceof ReplyHeader)) {
      return false;
    }
    if (peer_ == this) {
      return true;
    }
    ReplyHeader peer = (ReplyHeader) peer_;
    boolean ret = false;
    ret = (xid==peer.xid);
    if (!ret) return ret;
    ret = (zxid==peer.zxid);
    if (!ret) return ret;
    ret = (err==peer.err);
    if (!ret) return ret;
     return ret;
  }
  public int hashCode() {
    int result = 17;
    int ret;
    ret = (int)xid;
    result = 37*result + ret;
    ret = (int) (zxid^(zxid>>>32));
    result = 37*result + ret;
    ret = (int)err;
    result = 37*result + ret;
    return result;
  }
  public static String signature() {
    return "LReplyHeader(ili)";
  }
}
