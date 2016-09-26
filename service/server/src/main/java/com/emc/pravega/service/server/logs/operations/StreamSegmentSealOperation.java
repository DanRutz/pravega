/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emc.pravega.service.server.logs.operations;

import com.emc.pravega.common.Exceptions;
import com.emc.pravega.service.server.logs.SerializationException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Log Operation that indicates a StreamSegment has been sealed.
 */
public class StreamSegmentSealOperation extends StorageOperation {
    //region Members

    public static final byte OPERATION_TYPE = 2;
    private static final byte CURRENT_VERSION = 0;
    private long streamSegmentLength;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the StreamSegmentSealOperation class.
     *
     * @param streamSegmentId The Id of the StreamSegment to seal.
     */
    public StreamSegmentSealOperation(long streamSegmentId) {
        super(streamSegmentId);
        this.streamSegmentLength = -1;
    }

    protected StreamSegmentSealOperation(OperationHeader header, DataInputStream source) throws SerializationException {
        super(header, source);
    }

    //endregion

    //region StreamSegmentSealOperation Implementation

    /**
     * Gets a value indicating the length of the StreamSegment at the time of sealing.
     *
     * @return The length.
     */
    public long getStreamSegmentLength() {
        return this.streamSegmentLength;
    }

    /**
     * Sets the length of the StreamSegment at the time of sealing.
     *
     * @param value The length.
     */
    public void setStreamSegmentLength(long value) {
        Exceptions.checkArgument(value >= 0, "value", "StreamSegment Length must be a non-negative number.");
        this.streamSegmentLength = value;
    }

    //endregion

    //region Operation Implementation

    @Override
    protected byte getOperationType() {
        return OPERATION_TYPE;
    }

    @Override
    protected void serializeContent(DataOutputStream target) throws IOException {
        ensureSerializationCondition(this.streamSegmentLength >= 0, "StreamSegment Length has not been assigned for this entry.");
        target.writeByte(CURRENT_VERSION);
        target.writeLong(getStreamSegmentId());
        target.writeLong(this.streamSegmentLength);
    }

    @Override
    protected void deserializeContent(DataInputStream source) throws IOException, SerializationException {
        byte version = readVersion(source, CURRENT_VERSION);
        setStreamSegmentId(source.readLong());
        this.streamSegmentLength = source.readLong();
    }

    @Override
    public String toString() {
        return String.format(
                "%s, Length = %s",
                super.toString(),
                toString(this.streamSegmentLength, -1));
    }

    //endregion
}