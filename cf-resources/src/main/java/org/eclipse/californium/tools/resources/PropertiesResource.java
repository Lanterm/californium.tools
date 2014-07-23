package org.eclipse.californium.tools.resources;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;
import java.util.Scanner;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

/**
 * The resource represents an object of Java's Properties class. Each property
 * is represented by a child of the top resource. A client sends a GET request
 * to a property to retrieve its value, sends a PUT request to create or change
 * its value, and sends a DELETE request to delete a property. The resource
 * supports the If-None-Match option.
 * <p>
 * A separator ("." by default) is used to structure properties as resource
 * tree. For example, assume a Properties object with the keys
 * <code>Q, P, P.a, P.b, and P.c</code>. The resulting resource subtree looks
 * like this:
 * 
 * <pre>
 * top
 *  |---Q
 *  '---P
 *      |---a
 *      |---b
 *      '---c
 * </pre>
 * 
 * Note that if you use "." as separator, the path <code>/top/a.b.c</code> is
 * equal to <code>/top/a/b/c</code> in every regard. Use <code>null</code> as
 * separator to avoid structuring. In that case, the tree looks like this:
 * 
 * <pre>
 * top
 *  |---Q
 *  |---P
 *  |---P.a
 *  |---P.b
 *  '---P.c
 * </pre>
 * 
 * Note: The capabilities of the Properties class limits the capabilities of the
 * resource that represents it. It is not possible to observe a Properties
 * object, and therefore neither can we provide this to CoAP clients.
 */
public class PropertiesResource extends CoapResource {

	public static final String DEFAULT_SEPARATOR = ".";

	/** Separator for structuring the keys */
	private String separator = DEFAULT_SEPARATOR;
	
	/** The properties object to export */
	private Properties properties;

	/**
	 * Creates a new resource with an empty container for properties.
	 * 
	 * @param name the name of this resource
	 */
	public PropertiesResource(String name) {
		this(name, new Properties());
	}
	
	/**
	 * Creates a new resource that represents the specified properties as its
	 * child resources.
	 * 
	 * @param name the name of this resource
	 * @param properties the properties
	 */
	public PropertiesResource(String name, Properties properties) {
		super(name);
		this.properties = properties;
	}
	
	/**
	 * If the request is targeted at the root of the properties resource, we
	 * respond all properties. If the request is targeted at a child resource,
	 * i.e., a property key, we respond with the value of the property or a 4.04
	 * if the property is no found.
	 */
	public void handleGET(CoapExchange exchange) {
		try {
			Iterator<String> target = extractPropertyKey(exchange);
			if (!target.hasNext()) {
				// The request has been sent to the root of properties
				exchange.respond(ResponseCode.CONTENT, properties.toString());
			} else {
				String key = convertToKey(target);
				Object value = properties.get(key);
				if (value != null)
					exchange.respond(value.toString());
				else
					exchange.respond(ResponseCode.NOT_FOUND, "Did not find property "+key);
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			exchange.respond(ResponseCode.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * This method is not allowed.
	 */
	public void handlePOST(CoapExchange exchange) {
		super.handlePOST(exchange); // responds 4.05 (Method Not Allowed)
	}

	/**
	 * If the request is targeted at the root of the properties resource, we
	 * respond with a 4.05 (Method Not Allowed). If the request is targeted at a
	 * child resource, i.e., a key of a property, we set the payload of the
	 * request as new value of the property.
	 */
	public void handlePUT(CoapExchange exchange) {
		try {
			Iterator<String> target = extractPropertyKey(exchange);
			if (!target.hasNext()) {
				// The request has been sent to the root of properties
				exchange.respond(ResponseCode.METHOD_NOT_ALLOWED);
			} else {
				String key = convertToKey(target);
				String value = exchange.getRequestText();

				System.out.println("has inm: "+exchange.getRequestOptions().hasIfNoneMatch());
				if (exchange.getRequestOptions().hasIfNoneMatch()) {
					Object previous = properties.putIfAbsent(key, value);
					if (previous != null)
						exchange.respond(ResponseCode.PRECONDITION_FAILED);
					else 
						respondCreated(exchange, convertKey2Path(key));
					
				} else {
					Object previous = properties.setProperty(key, value);
					if (previous != null)
						exchange.respond(ResponseCode.CHANGED);
					else
						respondCreated(exchange, convertKey2Path(key));
				}
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			exchange.respond(ResponseCode.INTERNAL_SERVER_ERROR);
		}
	}
	
	private void respondCreated(CoapExchange exchange, String path) {
		Response response = new Response(ResponseCode.CREATED);
		response.getOptions().addLocationPath(path);
		exchange.respond(response);
	}
	
	/**
	 * If the request is targeted at the root of the properties resource, we
	 * respond with a 4.05 (Method Not Allowed). If the request is targeted at a
	 * child resource, i.e., a key of a property, we either remove the property
	 * and respond a 2.02 (Deleted) or a 4.04 (Not Found) if not found.
	 */
	public void handleDELETE(CoapExchange exchange) {		
		try {
			Iterator<String> target = extractPropertyKey(exchange);
			if (!target.hasNext()) {
				// The request has been sent to the root of properties
				exchange.respond(ResponseCode.METHOD_NOT_ALLOWED);
			} else {
				String key = convertToKey(target);
				Object value = properties.remove(key);
				if (value != null)
					exchange.respond(ResponseCode.DELETED);
				else
					exchange.respond(ResponseCode.NOT_FOUND);
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			exchange.respond(ResponseCode.INTERNAL_SERVER_ERROR);
		}
	}
	
	@Override // To find the resource that represents a property
	public Resource getChild(String name) {
		return this;
	}
	
	@Override // For discovery
	public Collection<Resource> getChildren() {
		return new DummyResourceCollection(properties, getURI(), separator);
	}
	
	public Properties getProperties() {
		return properties;
	}

	public void setProperties(Properties properties) {
		this.properties = properties;
	}
	
	/**
	 * Returns the separator.
	 * @return the separator
	 */
	public String getSeparator() {
		return separator;
	}

	/**
	 * Uses the specified String to separate property keys into child resources.
	 * For example, the default separator "." splits a key <code>a.b.c</code>
	 * into a tree of resources <code>/a/b/c</code>. Use <code>null</code> to
	 * avoid separation, i.e., <code>a.b.c</code> become <code>/a.b.c</code>.
	 * 
	 * @param separator the separator
	 */
	public void setSeparator(String separator) {
		this.separator = separator;
	}
	
	// Implementation
	
	/**
	 * Creates an iterator over the URI path that the request contains in its
	 * options. Iterates over them so that only the elements AFTER the root
	 * resource of the properties remain. The remaining elements specify the
	 * property (key) that the request is targeted at. If the iterator has no
	 * remaining elements, the request has been targeted to the properties root.
	 * 
	 * @param exchange the exchange
	 * @return an iterator with the remaining elements of the URI path.
	 */
	private Iterator<String> extractPropertyKey(CoapExchange exchange) {
		Iterator<String> target = exchange.getRequestOptions().getURIPaths().iterator();
		Scanner scanner = new Scanner(getURI());
		try {
			scanner.useDelimiter("/");
			while(scanner.hasNext()) {
				String expected = scanner.next();
				String actual = target.next();
				if (!expected.equals(actual)) {
					// This should never happen!
					throw new RuntimeException("URIPath does not match with the path of this resource");
				}
			}
		} finally {
			scanner.close();
		}
		return target;
	}
	
	/**
	 * Converts the specified iterator to the actual key the request is targeted
	 * at.
	 */
	private String convertToKey(Iterator<String> target) {
		StringBuilder key = new StringBuilder();
		while (target.hasNext())
			key.append(target.next()).append(getSeparator());
		int length = key.length();
		return (length == 0) ? "" : key.substring(0, length - 1);
	}
	
	/**
	 * Converts the specified key into the path to the resource that represent
	 * it.
	 */
	private String convertKey2Path(String key) {
		return key.replace(separator, "/");
	}
	
	/**
	 * This class is used to allow the discovery mechanism of the server ({@link
	 * LinkFormat}) to find the paths to the properties. Alternatively, the
	 * constructor could build a common resource tree from the given properties.
	 * However, Java's Properties does not able to make changes detectable,
	 * i.e., when properties are added or removed. To avoid excessive rebuilding
	 * of the resource tree, we decided to use this kind of virtual resource
	 * collection.
	 */
	private static class DummyResourceCollection extends AbstractCollection<Resource> implements Iterator<Resource> {
		
		/** Enumeration of all properties */
		private Enumeration<?> keys;
		
		/** The current property that is to be discovered */
		private Object current;
		
		/** The path to the PropertyResource */
		private String path;
		
		/** The separator */
		private String separator;
		
		/** Dummy resource that is used by {@link LinkFormat} to find all properties. */ 
		private Resource dummy = new CoapResource("") {
			@Override public String getPath() { return path; }
			@Override public String getName() { return getCurrentUri(); }
		};
		
		private String getCurrentUri() {
			String uri = current.toString();
			return separator == null ? uri : uri.replace(separator, "/");
		}
		
		// Constructor
		private DummyResourceCollection(Properties props, String parent, String separator) {
			this.keys = props.propertyNames();
			this.path = parent + "/";
			this.separator = separator;
		}
		
		// Functions of Iterator
	    public boolean hasNext() {
	    	return keys.hasMoreElements();
	    }
	    
	    public Resource next() { 
	    	current = keys.nextElement();
	    	return dummy;
	    }
	    
	    // Functions of AbstractCollection
		@Override public Iterator<Resource> iterator() { return this; }
		@Override public int size() { return 0; }
	}
}
