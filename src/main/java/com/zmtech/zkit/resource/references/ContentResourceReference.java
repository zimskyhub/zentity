package com.zmtech.zkit.resource.references

import com.zmtech.zkit.context.impl.ExecutionContextFactoryImpl;
import com.zmtech.zkit.resource.impl.ResourceFacadeImpl;
import com.zmtech.zkit.util.ObjectUtil;

import javax.jcr.*;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;


public class ContentResourceReference extends BaseResourceReference {
    protected final static Logger logger = LoggerFactory.getLogger(ContentResourceReference.class);
    public final static String locationPrefix = "content://";

    public String location;
    public String repositoryName;
    public String nodePath;

    protected javax.jcr.Node theNode = null;

    ContentResourceReference() { }

    @Override
    public ResourceReference init(String location, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf;

        this.location = location;
        // TODO: change to not rely on URI, or to encode properly
        URI locationUri;
        try {
            locationUri = new URI(location);
            repositoryName = locationUri.getHost();
            nodePath = locationUri.getPath();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return this;
    }

    private ResourceReference init(String repositoryName, javax.jcr.Node node, ExecutionContextFactoryImpl ecf) {
        this.ecf = ecf;

        this.repositoryName = repositoryName;
        try {
            this.nodePath = node.getPath();
            this.location = "${locationPrefix}${repositoryName}${nodePath}";
            this.theNode = node;
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        return this;
    }

    @Override
    public ResourceReference createNew(String location) {
        ContentResourceReference resRef = new ContentResourceReference();
        resRef.init(location, ecf);
        return resRef;
    }
    @Override
    public String getLocation() { return this.location; }

    @Override
    public InputStream openStream() {
        javax.jcr.Node node = getNode();
        if (node == null) return null;
        Node contentNode = null;
        try {
            contentNode = node.getNode("jcr:content");
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        if (contentNode == null) throw new IllegalArgumentException("Cannot get stream for content at [${repositoryName}][${nodePath}], has no jcr:content child node");
        Property dataProperty = null;
        try {
            dataProperty = contentNode.getProperty("jcr:data");
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        if (dataProperty == null) throw new IllegalArgumentException("Cannot get stream for content at [${repositoryName}][${nodePath}], has no jcr:content.jcr:data property");
        try {
            return dataProperty.getBinary().getStream();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("The getOutputStream method is not supported for JCR, use putStream() instead");
    }

    @Override public String getText() { return ObjectUtil.getStreamText(openStream()); }

    @Override public boolean supportsAll() { return true; }

    @Override public boolean supportsUrl() { return false; }
    @Override
    public URL getUrl() { return null; }

    @Override public boolean supportsDirectory() { return true; }
    @Override public boolean isFile() {
        javax.jcr.Node node = getNode();
        if (node == null) return false;
        try {
            return node.isNodeType("nt:file");
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        return false;
    }
    @Override public boolean isDirectory() {
        javax.jcr.Node node = getNode();
        if (node == null) return false;
        try {
            return node.isNodeType("nt:folder");
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        return false;
    }
    @Override
    public List<ResourceReference> getDirectoryEntries() {
        List<ResourceReference> dirEntries = new LinkedList();
        javax.jcr.Node node = getNode();
        if (node == null) return dirEntries;

        NodeIterator childNodes = null;
        try {
            childNodes = node.getNodes();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        while (childNodes.hasNext()) {
            javax.jcr.Node childNode = childNodes.nextNode();
            dirEntries.add(new ContentResourceReference().init(repositoryName, childNode, ecf));
        }
        return dirEntries;
    }
    // TODO: consider overriding findChildFile() to let the JCR impl do the query
    // ResourceReference findChildFile(String relativePath)

    @Override public boolean supportsExists() { return true; }
    @Override public boolean getExists() {
        if (theNode != null) return true;
        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName);
        try {
            return session.nodeExists(nodePath);
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        return true;
    }

    @Override public boolean supportsLastModified() { return true; }
    @Override public long getLastModified() {
        try {
            return getNode() != null?getNode().getProperty("jcr:lastModified") !=null?getNode().getProperty("jcr:lastModified").getDate() != null?getNode().getProperty("jcr:lastModified").getDate().getTimeInMillis():null:null:null;
        } catch (PathNotFoundException e) {
            return System.currentTimeMillis();
        } catch (ValueFormatException e) {
            e.printStackTrace();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
    }

    @Override public boolean supportsSize() { true; }
    @Override public long getSize() {
        try {
            return getNode()!=null?getNode().getProperty("jcr:content/jcr:data")!= null? getNode().getProperty("jcr:content/jcr:data").getLength() : null:null;
        } catch (PathNotFoundException e) {
            return 0;
        } catch (ValueFormatException e) {
            e.printStackTrace();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
    }

    @Override public boolean supportsWrite() { return true; }

    @Override public void putText(String text) { putObject(text); }
    @Override public void putStream(InputStream stream) { putObject(stream); }
    protected void putObject(Object obj) {
        if (obj == null) {
            logger.warn("Data was null, not saving to resource [${getLocation()}]");
            return;
        }
        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName);
        javax.jcr.Node fileNode = getNode();
        javax.jcr.Node fileContent = null;
        if (fileNode != null) {
            try {
                fileContent = fileNode.getNode("jcr:content");
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        } else {
            // first make sure the directory exists that this is in
            List<String> nodePathList = new ArrayList<>(Arrays.asList(nodePath.split("/")));
            // if nodePath started with a '/' the first element will be empty
            if (nodePathList.get(0) == "") nodePathList.remove(0);
            // remove the filename to just get the directory
            nodePathList.remove(nodePathList.size()-1);
            javax.jcr.Node folderNode = findDirectoryNode(session, nodePathList, true);

            // now create the node
            try {
                fileNode = folderNode.addNode(getFileName(), "nt:file");
                fileContent = fileNode.addNode("jcr:content", "nt:resource");
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        }
        try {
            fileContent.setProperty("jcr:mimeType", getContentType());
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        // fileContent.setProperty("jcr:encoding", ?)
        Calendar lastModified = Calendar.getInstance(); lastModified.setTimeInMillis(System.currentTimeMillis());
        try {
            fileContent.setProperty("jcr:lastModified", lastModified);
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        if (obj instanceof CharSequence) {
            try {
                fileContent.setProperty("jcr:data", session.getValueFactory().createValue(obj.toString()));
            } catch (RepositoryException e) {
                e.printStackTrace();
            }
        } else if (obj instanceof InputStream) {
            fileContent.setProperty("jcr:data", session.getValueFactory().createBinary((InputStream) obj));
        } else if (obj == null) {
            fileContent.setProperty("jcr:data", session.getValueFactory().createValue(""));
        } else {
            throw new IllegalArgumentException("Cannot save content for obj with type ${obj.class.name}");
        }

        try {
            session.save();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
    }

    private static javax.jcr.Node findDirectoryNode(Session session, List<String> pathList, boolean create) {
        Node rootNode = null try {
            rootNode = session.getRootNode();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        javax.jcr.Node folderNode = rootNode;
        if (pathList) {
            for (String nodePathElement : pathList) {
                try {
                    if (folderNode.hasNode(nodePathElement)) {
                        folderNode = folderNode.getNode(nodePathElement);
                    } else {
                        if (create) {
                            folderNode = folderNode.addNode(nodePathElement, "nt:folder");
                        } else {
                            folderNode = null;
                            break;
                        }
                    }
                } catch (RepositoryException e) {
                    e.printStackTrace();
                }
            }
        }
        return folderNode;
    }

    public void move(String newLocation) {
        if (!newLocation.startsWith(locationPrefix))
            throw new IllegalArgumentException("New location [${newLocation}] is not a content location, not moving resource at ${getLocation()}");

        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName)

        ResourceReference newRr = ecf.getResource().getLocationReference(newLocation);
        if (!newRr instanceof ContentResourceReference)
            throw new IllegalArgumentException("New location [${newLocation}] is not a content location, not moving resource at ${getLocation()}")
        ContentResourceReference newCrr = (ContentResourceReference) newRr;

        // make sure the target folder exists
        List<String> nodePathList = new ArrayList<>(Arrays.asList(newCrr.getNodePath().split('/')));
        if (nodePathList && nodePathList[0] == "") nodePathList.remove(0);
        if (nodePathList) nodePathList.remove(nodePathList.size()-1);
        findDirectoryNode(session, nodePathList, true);

        session.move(this.getNodePath(), newCrr.getNodePath());
        session.save();

        this.theNode = null;
    }

    @Override public ResourceReference makeDirectory(String name) {
        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName);
        findDirectoryNode(session, [name], true)
        return new ContentResourceReference().init("${location}/${name}", ecf);
    }
    @Override public ResourceReference makeFile(String name) {
        ContentResourceReference newRef = (ContentResourceReference) new ContentResourceReference().init("${location}/${name}", ecf)
        newRef.putObject(null);
        return newRef;
    }
    @Override public boolean delete() {
        javax.jcr.Node curNode = getNode();
        if (curNode == null) return false;

        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName);
        session.removeItem(nodePath);
        session.save();

        this.theNode = null;
        return true;
    }

    private javax.jcr.Node getNode() {
        if (theNode != null) return theNode;
        Session session = ((ResourceFacadeImpl) ecf.getResource()).getContentRepositorySession(repositoryName);
        return session.nodeExists(nodePath) ? session.getNode(nodePath) : null;
    }
}
