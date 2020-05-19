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

package org.apache.zookeeper.server.quorum;

import org.apache.jute.*;
import org.apache.yetus.audience.InterfaceAudience;

@InterfaceAudience.Public
public class QuorumAuthPacket implements Record {
  private long magic;
  private int status;
  private byte[] token;
  public QuorumAuthPacket() {
  }
  public QuorumAuthPacket(
        long magic,
        int status,
        byte[] token) {
    this.magic=magic;
    this.status=status;
    this.token=token;
  }
  public long getMagic() {
    return magic;
  }
  public void setMagic(long m_) {
    magic=m_;
  }
  public int getStatus() {
    return status;
  }
  public void setStatus(int m_) {
    status=m_;
  }
  public byte[] getToken() {
    return token;
  }
  public void setToken(byte[] m_) {
    token=m_;
  }
  public void serialize(OutputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(this,tag);
    a_.writeLong(magic,"magic");
    a_.writeInt(status,"status");
    a_.writeBuffer(token,"token");
    a_.endRecord(this,tag);
  }
  public void deserialize(InputArchive a_, String tag) throws java.io.IOException {
    a_.startRecord(tag);
    magic=a_.readLong("magic");
    status=a_.readInt("status");
    token=a_.readBuffer("token");
    a_.endRecord(tag);
}
  public String toString() {
    try {
      java.io.ByteArrayOutputStream s =
        new java.io.ByteArrayOutputStream();
      CsvOutputArchive a_ =
        new CsvOutputArchive(s);
      a_.startRecord(this,"");
    a_.writeLong(magic,"magic");
    a_.writeInt(status,"status");
    a_.writeBuffer(token,"token");
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
    if (!(peer_ instanceof QuorumAuthPacket)) {
      throw new ClassCastException("Comparing different types of records.");
    }
    QuorumAuthPacket peer = (QuorumAuthPacket) peer_;
    int ret = 0;
    ret = (magic == peer.magic)? 0 :((magic<peer.magic)?-1:1);
    if (ret != 0) return ret;
    ret = (status == peer.status)? 0 :((status<peer.status)?-1:1);
    if (ret != 0) return ret;
    {
      byte[] my = token;
      byte[] ur = peer.token;
      ret = org.apache.jute.Utils.compareBytes(my,0,my.length,ur,0,ur.length);
    }
    if (ret != 0) return ret;
     return ret;
  }
  public boolean equals(Object peer_) {
    if (!(peer_ instanceof QuorumAuthPacket)) {
      return false;
    }
    if (peer_ == this) {
      return true;
    }
    QuorumAuthPacket peer = (QuorumAuthPacket) peer_;
    boolean ret = false;
    ret = (magic==peer.magic);
    if (!ret) return ret;
    ret = (status==peer.status);
    if (!ret) return ret;
    ret = org.apache.jute.Utils.bufEquals(token,peer.token);
    if (!ret) return ret;
     return ret;
  }
  public int hashCode() {
    int result = 17;
    int ret;
    ret = (int) (magic^(magic>>>32));
    result = 37*result + ret;
    ret = (int)status;
    result = 37*result + ret;
    ret = java.util.Arrays.toString(token).hashCode();
    result = 37*result + ret;
    return result;
  }
  public static String signature() {
    return "LQuorumAuthPacket(liB)";
  }
}
