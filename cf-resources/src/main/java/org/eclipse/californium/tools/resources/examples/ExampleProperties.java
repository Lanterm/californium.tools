package org.eclipse.californium.tools.resources.examples;

import java.util.Properties;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.tools.resources.PropertiesResource;

public class ExampleProperties {

	public static void main(String[] args) {
		
		/*
		 * Creates a server with a resource called "properties" that represents
		 * the following Properties object.
		 */
		
		Properties properties = new Properties();
		properties.put("A",  "aaa");
		properties.put("B",  "bbb");
		properties.put("C",  "ccc");
		properties.put("D",  "ddd");
		properties.put("D.1", 111 ); // integer
		properties.put("D.2", 222 ); // integer
		properties.put("E",  "eee" );
		properties.put("F",  "fff" );
		properties.put("F.1.a", Math.PI); // double
		properties.put("G",  "ggg");
		properties.put("H",  "hhh");
		
		// Notice that property "F" and "F.1.a" exist but not "F.1"
		
		CoapServer server = new CoapServer();
		server.add(new PropertiesResource("properties", properties));
		server.start();
	}
	
}
