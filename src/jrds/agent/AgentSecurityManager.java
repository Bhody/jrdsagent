package jrds.agent;

import java.io.File;
import java.io.FilePermission;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.Permission;
import java.security.Permissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import jrds.agent.Start.PROTOCOL;

public class AgentSecurityManager extends SecurityManager {

    private final static class PrivilegHolder  {
        public boolean privileged = false;
    }
    private static final ThreadLocal<PrivilegHolder> Privilege =
            new ThreadLocal<PrivilegHolder>() {
        @Override
        protected PrivilegHolder initialValue() {
            return new PrivilegHolder();
        }
    };

    private final Set<String> permUsed;
    private final Set<String> permCreated;

    private final Pattern procinfoPattern =  Pattern.compile("/proc/[0-9]+/(cmdline|io|stat|statm)");

    private final boolean debugPerm;
    private final Permissions allowed = new Permissions();
    private final Set<String> filesallowed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public AgentSecurityManager(boolean debugPerm, PROTOCOL proto) {
        this.debugPerm = debugPerm;

        if(debugPerm) {
            permUsed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            permCreated = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    for(String i: new HashSet<String>(permCreated)) {
                        if(permUsed.contains(i + " =")) {
                            permCreated.remove(i);
                        }
                    }
                    for(String i: permCreated){
                        permUsed.add(i +" +");
                    }

                    for(String p: new TreeSet<String>(permUsed)) {
                        System.out.println(p);
                    }
                }
            });
        } else {
            permUsed = null;
            permCreated = null;
        }

        //Add the java home to readable files
        Permission newPerm = new FilePermission(System.getProperty("java.home") + File.separator + "-", "read");
        allowed.add(newPerm);
        if(debugPerm) {
            permCreated.add(newPerm.toString());
        }

        Map<String, Set<Permission>> permsSets;
        try {
            permsSets = getPermsSets();
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        for(Permission i: permsSets.get("common")) {
            allowed.add(i);
            if(debugPerm) {
                permCreated.add(i.toString());
            }
        }

        for(Permission i: permsSets.get(proto.name())) {
            allowed.add(i);
            if(debugPerm) {
                permCreated.add(i.toString());
            }
        }

        allowed.setReadOnly();

    }

    public void checkPermission(Permission perm) {
        // They can be an undefined number of security.provider.*
        // Used by jmxmp
        if(perm instanceof java.security.SecurityPermission
                && perm.getName().startsWith("getProperty.security.provider") ) {
            return;
        }
        if(perm instanceof java.io.FilePermission
                && "read".equals(perm.getActions()) ) {
            String name = perm.getName();
            if(filesallowed.contains(name)) {
                return;
            }
            // Already allowed, don't check any more
            if(allowed.implies(perm)) {
                if(debugPerm) {
                    permUsed.add(perm.toString() + " =");
                }
                filesallowed.add(name);
                return;
            }
            // Perhaps it's in the allowed /proc/<pid>/... files
            if(procinfoPattern.matcher(name).matches()) {
                if(debugPerm) {
                    permUsed.add("(\"java.io.FilePermission\" \"" + procinfoPattern.pattern() + "\" \"read\") =");
                }
                return;                        
            }
            // Only non hidden folder are allowed, for file system usage
            // If it call itself, privileg will be set to true, 
            // so it can check isDirectory and isHidden
            PrivilegHolder privileged = Privilege.get();
            if(privileged.privileged) {
                return;
            } else {
                File fullpath = new File(name);
                privileged.privileged = true;
                boolean allowed = false;
                try {
                    allowed = fullpath.isDirectory() && ! fullpath.isHidden();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    privileged.privileged = false;
                }
                if(allowed) {
                    if(debugPerm) {
                        permUsed.add(perm.toString() + " =");
                    }
                    filesallowed.add(name);
                    return;
                }
            }
        }
        if(allowed.implies(perm)) {
            if(debugPerm) {
                permUsed.add(perm.toString() + " =");
            }
            return;
        }
        try {
            super.checkPermission(perm);
        } catch (SecurityException e) {
            if(debugPerm) {
                permUsed.add(perm.toString() + " -");
            } else {
                throw e;
            }
        }
    }

    private Map<String, Set<Permission>> getPermsSets() throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Map<String, String[][]> permsDescription = new HashMap<String, String[][]>();
        Map<String, Set<Permission>> permsSets = new HashMap<String, Set<Permission>>();

        permsDescription.put("common", new String[][] {
            new String[] { "java.io.FilePermission", "/proc", "read" },
            new String[] { "java.io.FilePermission", "/proc/*", "read" },
            new String[] { "java.io.FilePermission", "/proc/net/*", "read" },
            new String[] { "java.io.FilePermission", "/proc/net/rpc/nfs", "read" },
            new String[] { "java.lang.RuntimePermission", "accessDeclaredMembers" },
            new String[] { "java.lang.RuntimePermission", "createClassLoader" },
            new String[] { "java.lang.RuntimePermission", "getFileSystemAttributes" },
            new String[] { "java.lang.RuntimePermission", "loadLibrary.net" }, // Needed on windows
            new String[] { "java.lang.RuntimePermission", "modifyThread" },
            new String[] { "java.lang.RuntimePermission", "modifyThreadGroup" },
            new String[] { "java.lang.RuntimePermission", "readFileDescriptor" },
            new String[] { "java.lang.RuntimePermission", "setContextClassLoader" },
            new String[] { "java.lang.RuntimePermission", "writeFileDescriptor" },
            new String[] { "java.lang.reflect.ReflectPermission", "suppressAccessChecks" },
            new String[] { "java.util.logging.LoggingPermission", "control", "" },
            new String[] { "java.util.PropertyPermission", "jdk.logging.allowStackWalkSearch", "read" }, 
            new String[] { "java.util.PropertyPermission", "jdk.net.ephemeralPortRange.high", "read" },  // Needed on windows
            new String[] { "java.util.PropertyPermission", "jdk.net.ephemeralPortRange.low", "read" },   // Needed on windows
        });
        permsDescription.put(PROTOCOL.rmi.name(), new String[][] {
            new String[] { "java.io.SerializablePermission", "enableSubstitution" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.reflect" },
            new String[] { "java.lang.RuntimePermission", "getClassLoader" },
            new String[] { "java.lang.RuntimePermission", "getProtectionDomain" },
            new String[] { "java.lang.RuntimePermission", "loadLibrary.rmi" },     // Needed on jdk 6
            new String[] { "java.lang.RuntimePermission", "reflectionFactoryAccess" },
            new String[] { "java.lang.RuntimePermission", "sun.rmi.runtime.RuntimeUtil.getInstance" },
            new String[] { "java.net.NetPermission", "specifyStreamHandler" },
            new String[] { "java.net.SocketPermission", "*", "accept,resolve" },
            new String[] { "java.security.SecurityPermission", "getPolicy" },
            new String[] { "java.util.PropertyPermission", "java.rmi.server.RMIClassLoaderSpi", "read" },
            new String[] { "java.util.PropertyPermission", "java.rmi.server.codebase", "read" },
            new String[] { "java.util.PropertyPermission", "java.rmi.server.useCodebaseOnly", "read" },
            new String[] { "java.util.PropertyPermission", "jdk.internal.lambda.dumpProxyClasses", "read" },
            new String[] { "java.util.PropertyPermission", "sun.io.serialization.extendedDebugInfo", "read" },
            new String[] { "java.util.PropertyPermission", "sun.net.maxDatagramSockets", "read" },
            new String[] { "java.util.PropertyPermission", "sun.rmi.dgc.ackTimeout", "read" },
            new String[] { "java.util.PropertyPermission", "sun.rmi.loader.logLevel", "read" },
            new String[] { "java.util.PropertyPermission", "sun.rmi.transport.connectionTimeout", "read" },
            new String[] { "java.util.PropertyPermission", "sun.rmi.transport.tcp.handshakeTimeout", "read" },
            new String[] { "java.util.PropertyPermission", "sun.rmi.transport.tcp.responseTimeout", "read" },
            new String[] { "java.util.PropertyPermission", "sun.util.logging.disableCallerCheck", "read" },
        });
        permsDescription.put(PROTOCOL.jmx.name(), new String[][] {
            new String[] { "java.io.SerializablePermission", "enableSubstitution" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.reflect" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.reflect.misc" },
            new String[] { "java.lang.RuntimePermission", "getClassLoader" },
            new String[] { "java.net.NetPermission", "getProxySelector" },
            new String[] { "java.net.SocketPermission", "*", "accept,listen,resolve" },
            new String[] { "java.security.SecurityPermission", "getPolicy" },
            new String[] { "java.util.PropertyPermission", "java.rmi.server.hostname", "read" },
            new String[] { "java.util.PropertyPermission", "java.rmi.server.randomIDs", "read" },
            new String[] { "java.util.PropertyPermission", "socksProxyHost", "read" },
            new String[] { "java.util.PropertyPermission", "sun.net.maxDatagramSockets", "read" },
            new String[] { "javax.management.MBeanPermission", "-#-[-]", "getClassLoaderRepository" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#-[jrds:type=agent]", "getClassLoaderFor" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#prepare[jrds:type=agent]", "invoke" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#query[jrds:type=agent]", "invoke" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#Uptime[jrds:type=agent]", "getAttribute" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#-[jrds:type=agent]", "getClassLoaderFor" },
            new String[] { "javax.management.MBeanPermission", "sun.management.RuntimeImpl#Uptime[java.lang:type=Runtime]", "getAttribute" },
        });
        permsDescription.put(PROTOCOL.jmxmp.name(), new String[][] {
            new String[] { "java.io.FilePermission", "/dev/random", "read" },
            new String[] { "java.io.FilePermission", "/dev/urandom", "read" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.reflect" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.reflect.misc" },
            new String[] { "java.lang.RuntimePermission", "accessClassInPackage.sun.security.provider" },
            new String[] { "java.net.SocketPermission", "*", "accept,resolve" },
            new String[] { "java.security.SecurityPermission", "getProperty.securerandom.source" },
            new String[] { "java.security.SecurityPermission", "putProviderProperty.SUN" },
            new String[] { "java.util.PropertyPermission", "com.sun.jmx.remote.bug.compatible", "read" },
            new String[] { "java.util.PropertyPermission", "java.security.egd", "read" },
            new String[] { "java.util.PropertyPermission", "os.arch", "read" },
            new String[] { "java.util.PropertyPermission", "sun.net.maxDatagramSockets", "read" },
            new String[] { "java.util.PropertyPermission", "sun.util.logging.disableCallerCheck", "read" },
            new String[] { "javax.management.MBeanPermission", "-#-[-]", "getClassLoaderRepository" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#-[jrds:type=agent]", "getClassLoaderFor" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#prepare[jrds:type=agent]", "invoke" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#query[jrds:type=agent]", "invoke" },
            new String[] { "javax.management.MBeanPermission", "jrds.agent.RProbeJMXImpl#Uptime[jrds:type=agent]", "getAttribute" },
            new String[] { "javax.management.MBeanPermission", "sun.management.RuntimeImpl#Uptime[java.lang:type=Runtime]", "getAttribute" },
        });
        Class<?>[][] typeVector = new Class[][]{
            new Class[] { String.class },
            new Class[] { String.class, String.class },
        };
        for(String name: new String[]{"common", PROTOCOL.rmi.name(), PROTOCOL.jmx.name(), PROTOCOL.jmxmp.name()}) {
            Set<Permission> current = new HashSet<Permission>();
            permsSets.put(name, current);
            for(String[] a: permsDescription.get(name)) {
                String className = a[0];
                String[] argVector = Arrays.copyOfRange(a, 1, a.length);
                Class<?> cl = Start.class.getClassLoader().loadClass(className);
                Constructor<?> c = cl.getConstructor( typeVector[argVector.length - 1]);
                Permission newPerm = (Permission) c.newInstance((Object[])argVector);
                current.add(newPerm);
            }
        }
        return permsSets;
    }

}
