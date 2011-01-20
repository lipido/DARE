package es.uvigo.ei.sing.dare.resources;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.junit.Ignore;
import org.junit.Test;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.core.util.MultivaluedMapImpl;

public class ExecuteRobotResourceTest {


    private static final URI BASE_URI = UriBuilder.fromUri("http://localhost/")
            .port(8080).path("DARE").build();

    private WebResource appResource;

    public ExecuteRobotResourceTest() {
        Client c = Client.create();
        appResource = c.resource(BASE_URI);
    }

    @Test
    public void existsPostMethod() throws Exception {
        doPostOnMinilanguageResource(new MultivaluedMapImpl() {
            {
                add("transformer",
                        "url | xpath('//a/@href') | patternMatcher('(http://.*)') ");
                add("input", "http://www.google.es");
                add("input", "http://www.esei.uvigo.es");
            }
        });
    }

    @Test
    public void onWrongTransformerThrowsException() throws Exception {
        try {
            doPostOnMinilanguageResource(new MultivaluedMapImpl() {
                {
                    add("transformer",
                            "ur xpath('//a/@href') | patternMatcher('(http://.*)') ");
                    add("input", "http://www.google.es");
                }
            });
        } catch (UniformInterfaceException e) {
            assertThat(e.getResponse().getStatus(), equalTo(400));
        }
    }

    private void doPostOnMinilanguageResource(MultivaluedMapImpl postEntity) {
        doPostOnMinilanguageResource(ExecutionResult.class, postEntity);
    }

    private <T> T doPostOnMinilanguageResource(Class<T> type,
            MultivaluedMapImpl postEntity) {
        return appResource.path(ExecuteRobotResource.PATH).type(
                MediaType.APPLICATION_FORM_URLENCODED).accept(
                MediaType.APPLICATION_JSON_TYPE).post(type, postEntity);
    }

    @Test
    @Ignore("review string editor execution handling")
    public void testErrorExecuting() throws Exception {
        try {
            doPostOnMinilanguageResource(new MultivaluedMapImpl() {
                {
                    add("transformer",
                            "url | xpath('//a/@href') | patternMatcher('(http://.*)') ");
                    add("input", "http://www." + UUID.randomUUID() + ".es");
                }
            });
            fail("it must fail since it can't be executed because one of the inputs is wrong");
        } catch (UniformInterfaceException e) {
            assertThat(e.getResponse().getStatus(), equalTo(500));
        }
    }

    @Test
    public void testReturnResults() throws Exception {
        ExecutionResult result = doPostOnMinilanguageResource(
                ExecutionResult.class, new MultivaluedMapImpl() {
                    {
                        add("transformer",
                                "url | xpath('//a/@href') | patternMatcher('(http://.*)') ");
                        add("input", "http://www.google.es");
                        add("input", "http://www.esei.uvigo.es");
                    }
                });
        assertNotNull(result);
        assertNotNull(result.getLines());
        assertFalse(result.getLines().isEmpty());
    }

    @Test
    public void itReturnsTheTimeElapsed() throws Exception {
        ExecutionResult result = doPostOnMinilanguageResource(
                ExecutionResult.class, new MultivaluedMapImpl() {
                    {
                        add("transformer",
                                "url | xpath('//a/@href') | patternMatcher('(http://.*)') ");
                        add("input", "http://www.google.es");
                        add("input", "http://www.esei.uvigo.es");
                    }
                });
        assertThat(result.getExecutionTime(), greaterThan(0l));
    }

}
