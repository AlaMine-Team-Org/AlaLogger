package day.alacraft.alalogger.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

// @implements TC-LOG-007
class ApiExceptionTest {

    @Test
    void keepsItsCodeThroughSerialisation() throws Exception {
        ApiException original = new ApiException(ApiError.client(ApiErrorCode.RATE_LIMITED, "slow down"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        ApiException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (ApiException) in.readObject();
        }

        assertEquals(ApiErrorCode.RATE_LIMITED, copy.code(), "code() must not throw on a deserialised copy");
    }
}
