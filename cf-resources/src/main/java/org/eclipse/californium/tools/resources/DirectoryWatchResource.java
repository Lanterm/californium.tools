package org.eclipse.californium.tools.resources;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;


/**
 * A FileWatchResource is a resource to export a directory, its files, and
 * recursively its subdirectories. The resource builds up and maintains a
 * resource tree analogous to the associated file tree where each file is
 * represented by one resource. The root file must be a directory, not a single
 * file.
 * <p>
 * Only GET requests are allowed. A resource representing a directory will
 * respond with a list of its direct children, i.e., the files and directory
 * names it contains. A resource representing a file will respond with the
 * content of the file. If the file is large, the response may be sent
 * blockwise.
 * <p>
 * A resource representing a file associates certain file name suffixes with a
 * content format. For instance, the content format of a file which's name ends
 * with .jpg is regarded as image/jpeg {@link MediaTypeRegistry#IMAGE_JPEG}. If
 * possible, a resource includes its content format in its attributes and adds a
 * content format option to responses. Files with unrecognized suffixes have no
 * content format. If a request asks for an incompatible content format, the
 * resource responds with a 4.06 (Not Acceptable).
 * <p>
 * This and all child resources support CoAP observation relations. Observers of
 * a resource representing a file will be notified whenever the file content
 * changes (yields a 2.05 response) or is deleted (yields a 4.04 response).
 * Observers of a resource representing a directory will be notified whenever a
 * new file is added or deleted (yields a 2.05 with the updated list of children
 * as payload).
 * <p>
 * Note that file name changes are typically viewed by the file system as the
 * creation of a new file and the deletion of the original. As a result, all
 * observe relation with clients will be canceled when a file changes its name.
 * <p>
 * Security: Care has been taken that it is not possible to read the content of
 * a file outside of the directory specified in the constructor, i.e., a request
 * to /[this resource]/../foo will return a 4.04 (Not Found).
 */
public class DirectoryWatchResource extends CoapResource {

	/** The logger */
	private final static Logger LOGGER = Logger.getLogger(DirectoryWatchResource.class.getCanonicalName());
	
	/** The logger level */
	private final static Level LEVEL = Level.FINE;
	
	/** The watch service. */
	private WatchService watchService;
	
	/** The root path. */
	private Path rootPath;
	
	/** The implementation for GET/POST/PUT/DEL requests */
	private FileResource impl;
	
	/**
	 * Instantiates a resource that exports the directory at the specified path.
	 *
	 * @param name the name
	 * @param dirPath the path to the directory
	 * @throws IllegalArgumentException if the specified path is a file and not
	 *             a directory
	 */
	public DirectoryWatchResource(String name, String dirPath) {
		super(name);
		this.rootPath = Paths.get(dirPath);
		if (!isDirectory(rootPath))
			throw new IllegalArgumentException("The specified path is not a directory: "+dirPath);
		initializeWatcher();
		buildResourceTree();
		setObservable(true);
	}
	
	@Override // delegate
	public void handleGET(CoapExchange exchange) {
		impl.handleGET(exchange);
	}

	@Override // delegate
	public void handlePOST(CoapExchange exchange) {
		impl.handlePOST(exchange);
	}

	@Override // delegate
	public void handlePUT(CoapExchange exchange) {
		impl.handlePUT(exchange);
	}

	@Override // delegate
	public void handleDELETE(CoapExchange exchange) {
		impl.handleDELETE(exchange);
	}
	
	@Override // delegate
	public void add(Resource child) {
		super.add(child);
		changed();
	}
	
	@Override // delegate
	public boolean remove(Resource child) {
		boolean ret = super.remove(child);
		changed();
		return ret;
	}
	
	/**
	 * Initializes the watch service.
	 */
	protected void initializeWatcher() {
		try {
			this.watchService = FileSystems.getDefault().newWatchService();
			
			Thread thread = new Thread() {
				public void run() { processEvents(); } };
			thread.setDaemon(true);
			thread.start();
			
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Builds the resource tree where each file or directory is represented by a
	 * resource.
	 *
	 * @param filePath the file to the root directory
	 */
	protected void buildResourceTree() {
		try {
			if (LOGGER.isLoggable(LEVEL))
				LOGGER.log(LEVEL, "Build resource tree for directory "+rootPath.toAbsolutePath());
			impl = new FileResource(rootPath);
			registerFileTree(rootPath, this);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
    
	/**
	 * Recursively registers the specified root directory and all its files and 
	 * subdirectories.
	 *
	 * @param root the root
	 * @param resource the parent resource
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private void registerFileTree(final Path root, final Resource resource) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			
			private LinkedList<Resource> stack = new LinkedList<Resource>();
			private Resource current = resource;
			
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				stack.addLast(current);
				current = registerDirectory(dir, current);
				return FileVisitResult.CONTINUE;
			}
			
		    @Override
		    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
		    	CoapResource resource = new FileResource(file);
		        current.add(resource);
		        return FileVisitResult.CONTINUE;
		    }
			
		    @Override
		    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
		    	current = stack.pollLast();
		    	return FileVisitResult.CONTINUE;
		    }
		});
    }
	
    /**
     * Registers the specified directory and creates a new resource for it.
     *
     * @param path the path
     * @param parent the parent resource
     * @return the newly created resource
     * @throws IOException Signals that an I/O exception has occurred.
     */
    private Resource registerDirectory(Path path, Resource parent) throws IOException {
    	if (LOGGER.isLoggable(LEVEL))
    		LOGGER.log(LEVEL, "Register directory "+path);
    	path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    	if (path.equals(rootPath)) return this;
        FileResource resource = new FileResource(path);
        parent.add(resource);
        return resource;
    }
	
	/**
	 * Handle events delivered by the watch service.
	 */
	private void processEvents() {
		try {
			while (true) {
	
				WatchKey key;
				try {
					key = watchService.take();
				} catch (InterruptedException e) { 
					return;
				}
				
				List<WatchEvent<?>> events = key.pollEvents();
				for (WatchEvent<?> event:events) {
					WatchEvent.Kind<?> kind = event.kind();
					
					if (kind == StandardWatchEventKinds.OVERFLOW)
						continue;
					
					// Get the relative path from the root to the file the event
					// happened for
					Path name = cast(event).context();
					Path dir = (Path) key.watchable();
					Path file = dir.resolve(name);
					Path relative = rootPath.relativize(file);
					
					if (LOGGER.isLoggable(LEVEL))
						LOGGER.log(LEVEL, "Event "+kind+" at file "+file);
					if (kind == ENTRY_CREATE) {
						CoapResource parent = findResource(relative.getParent());
						if (isDirectory(file))
							registerFileTree(file, parent);
						else
							parent.add(new FileResource(file));
						
					} else if (kind == ENTRY_DELETE) {
						CoapResource resource = findResource(relative);
						if (resource != null) resource.delete();
						
					} else if (kind == ENTRY_MODIFY) {
						CoapResource resource = findResource(relative);
						if (resource != null) resource.changed();
					}
					
				}
				
				key.reset();
				
			}
		} catch (IOException e) {
			e.printStackTrace();
			
		} finally {
			try { // close the service
				if (LOGGER.isLoggable(LEVEL))
					LOGGER.log(LEVEL, "Close WatchService for directory "+rootPath);
				watchService.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Find the resource at the specified path.
	 *
	 * @param path the path
	 * @return the resource that represents the file
	 */
	private CoapResource findResource(Path path) {
		if (path == null) return this;
		Resource current = this;
		for (Path element:path) {
			current = current.getChild(element.toString());
			/*
			 * When we remove a directory, weirdly enough, we receive MODIFY
			 * events for its files after the directory has already been
			 * deleted. Therefore, we return null here.
			 */
			if (current == null) return null;
		}
		return (CoapResource) current;
	}
	
    @SuppressWarnings("unchecked")
    private static WatchEvent<Path> cast(WatchEvent<?> event) {
        return (WatchEvent<Path>) event;
    }
    
    /**
     * Returns true it the file at the specified path is a directory.
     *
     * @param path the path
     * @return true, if the path leads to a directory
     */
    private static boolean isDirectory(Path path) {
    	return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }
    
    /**
	 * This resource represents a file. The resource extracts the content format
	 * from the file name if possible. Only GET requests are allowed.
	 */
    public class FileResource extends CoapResource {
    	
    	/** The path to the file. */
	    private Path path;
    	
	    /** The file type, i.e., the content format. */
	    private FileType fileType;
    	
    	/**
	     * Instantiates a new file resource.
	     *
	     * @param path the path to the file
	     */
	    public FileResource(Path path) {
    		super(path.getFileName().toString());
    		this.path = path;
    		
    		this.fileType = FileType.getType(path.getFileName().toString());
    		if (fileType != null) {
    			getAttributes().addContentType(fileType.contentFormat);
    		}
    		setObservable(true);
    	}
    	
	    /**
		 * If this is a directory, responds with a list of subdirectories and
		 * files within. If this is a formal file, responds the file content.
		 */
		@Override
		public void handleGET(CoapExchange exchange) {
			try {
				exchange.accept();
				if (!validateContentFormat(exchange.getRequestOptions().getContentFormat())) {
					exchange.respond(ResponseCode.NOT_ACCEPTABLE);
					return;
				}
				
				if (isDirectory(path)) {
					String entries = Files.list(path)
						.map(new Function<Path, String>() {
							public String apply(Path t) { return rootPath.relativize(t).toString(); }})
						.collect(Collectors.joining("\n"));
					exchange.respond(entries);
				
				} else {
					byte[] content = Files.readAllBytes(path);
					if (fileType != null)
						exchange.respond(ResponseCode.CONTENT, content, fileType.contentFormat);
					else
						exchange.respond(ResponseCode.CONTENT, content);
					
				}
				
			} catch (Exception e) {
				LOGGER.log(LEVEL, e.getMessage(), e);
				exchange.respond(ResponseCode.INTERNAL_SERVER_ERROR);
			}
		}
		
		/**
		 * Returns true if the specified content format is compatible with the
		 * type of the file this resource represents.
		 *
		 * @param cf the content format of the request
		 * @return true, if the file is compatible
		 */
		private boolean validateContentFormat(int cf) {
			if (cf == MediaTypeRegistry.UNDEFINED) return true;
			if (fileType == null && cf == MediaTypeRegistry.TEXT_PLAIN) return true;
			if (fileType != null && cf == fileType.contentFormat) return true;
			else return false;
		}
		
		@Override
		public CoapResource add(CoapResource child) {
			super.add(child);
			changed();
			return this;
		}
		
		@Override
		public boolean remove(Resource child) {
			boolean ret = super.remove(child);
			changed();
			return ret;
		}
		
		@Override
		public void delete() {
			for (Resource child:getChildren())
				((CoapResource) child).delete();
			super.delete();
		}
		
		@Override
		public String toString() {
			return "FileResource("+getName()+")";
		}
    }
    
	/**
	 * The enum FileType helps to map file suffixes to its corresponding content
	 * format.
	 */
	private static enum FileType {
		
		TXT (".txt",  MediaTypeRegistry.TEXT_PLAIN),
		HTML(".html", MediaTypeRegistry.TEXT_HTML),
		CSV (".csv",  MediaTypeRegistry.TEXT_CSV),
		JSON(".json", MediaTypeRegistry.APPLICATION_JSON),
		GIF(".gif", MediaTypeRegistry.IMAGE_GIF),
		JPEG(".jpeg", MediaTypeRegistry.IMAGE_JPEG),
		JPG(".jpg", MediaTypeRegistry.IMAGE_JPEG),
		PNG(".png", MediaTypeRegistry.IMAGE_PNG),
		TIFF(".tiff", MediaTypeRegistry.IMAGE_TIFF),
		XML (".xml",  MediaTypeRegistry.APPLICATION_XML);

		/** The suffix. */
		private String suffix;
		
		/** The content format. */
		private int contentFormat;
		
		/**
		 * Instantiates a new file type.
		 *
		 * @param suffix the suffix
		 * @param contentFormat the content format
		 */
		private FileType(String suffix, int contentFormat) {
			this.suffix = suffix;
			this.contentFormat = contentFormat;
		}
		
		/**
		 * Gets the type associated with the specified file name.
		 *
		 * @param fileName the file name
		 * @return the file type
		 */
		public static FileType getType(String fileName) {
			for (FileType ft:values())
				if (fileName.endsWith(ft.suffix))
					return ft;
			return null;
		}
	}
}
