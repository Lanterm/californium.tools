package org.eclipse.californium.tools.resources.examples;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.tools.resources.DirectoryWatchResource;

public class ExampleDirectoryWatch {

	public static void main(String[] args) throws Exception {

		/*
		 * Creates a server with a resource called "filesystem" that exports all
		 * files within the directory it is executed in.
		 */
		CoapServer server = new CoapServer();
		server.add(new DirectoryWatchResource("filesystem", "."));
		server.start();

	}
	
	
}
