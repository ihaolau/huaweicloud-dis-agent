package com.huaweicloud.dis.agent.tailing;

import java.util.Map;

import com.huaweicloud.dis.agent.AgentContext;

/**
 * Interface for a sender that sends a buffer of records to an implementation- specific destination.
 *
 * @param <R> The record type.
 */
public interface ISender<R extends IRecord>
{
    /**
     * Sends the buffer to the implementation-specific destination. The buffer will be modified by this method and after
     * it returns it will have all the records that were succeesfully sent to be removed, and what remains are records
     * that were not successfully committed to destination. More specifically, if the status of the
     * {@link BufferSendResult} is:
     * <ul>
     * <li>{@code SUCEESS}: then the buffer is expected to be empty after the call.</li>
     * <li>{@code ERROR}: then the buffer is expected to be unchanged after the call, and it would contain the same
     * records as before.</li>
     * <li>{@code PARTIAL_FAILURE}: then the buffer is expected to have some records left in it, but the records that
     * were committed are removed.</li>
     * </ul>
     * Note that the {@link RecordBuffer#checkpointFile() buffer.checkpointFile()} and
     * {@link RecordBuffer#checkpointOffset() buffer.checkpointOffset()} will not be changed in any of the cases above.
     * <p>
     * These semantics make it safe to retry a buffer by calling this method again with the same instance (which is also
     * referenced in {@link BufferSendResult#getBuffer() result.getBuffer()}).
     *
     * @param buffer The buffer to send to destination. Will be modified by this call by removing all records that were
     *            succeeffully committed to the destination.
     * @return The result of the send operation. The {@link BufferSendResult#getBuffer() result.getBuffer()} is a
     *         reference to the input parameter.
     * @throws IllegalArgumentException
     */
    BufferSendResult<R> sendBuffer(RecordBuffer<R> buffer);
    
    /**
     * @return The agent context for this sender.
     */
    AgentContext getAgentContext();
    
    /**
     * @return The destination where the sender is targeting
     */
    String getDestination();
    
    Map<String, Object> getMetrics();
    
}
