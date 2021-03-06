/*
 * Copyright 2013 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.web.server.model;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.*;
import java.util.*;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import com.google.inject.persist.Transactional;

import org.hibernate.proxy.HibernateProxy;
import org.traccar.web.client.model.DataService;
import org.traccar.web.client.model.EventService;
import org.traccar.web.shared.model.*;

@Singleton
public class DataServiceImpl extends RemoteServiceServlet implements DataService {
    private static final long serialVersionUID = 1;

    @Inject
    private Provider<User> sessionUser;

    @Inject
    private Provider<ApplicationSettings> applicationSettings;

    @Inject
    private Provider<EntityManager> entityManager;

    @Inject
    private Provider<HttpServletRequest> request;

    @Inject
    private EventService eventService;

    @Override
    public void init() throws ServletException {
        super.init();

        /**
         * Perform database migrations
         */
        try {
            new DBMigrations().migrate(entityManager.get());
        } catch (Exception e) {
            throw new RuntimeException("Unable to perform DB migrations", e);
        }
    }

    EntityManager getSessionEntityManager() {
        return entityManager.get();
    }

    private void setSessionUser(User user) {
        HttpSession session = request.get().getSession();
        if (user != null) {
            session.setAttribute(CurrentUserProvider.ATTRIBUTE_USER_ID, user.getId());
        } else {
            session.removeAttribute(CurrentUserProvider.ATTRIBUTE_USER_ID);
        }
    }

    User getSessionUser() {
        return sessionUser.get();
    }

    @Transactional
    @RequireUser
    @Override
    public User authenticated() throws IllegalStateException {
        return fillUserSettings(new User(getSessionUser()));
    }

    @Transactional
    @Override
    public User login(String login, String password, boolean passwordHashed) {
        EntityManager entityManager = getSessionEntityManager();
        TypedQuery<User> query = entityManager.createQuery(
                "SELECT x FROM User x WHERE x.login = :login", User.class);
        query.setParameter("login", login);
        List<User> results = query.getResultList();

        if (results.isEmpty() || password.equals("")) throw new IllegalStateException();

        if (!results.get(0).getPassword().equals(
                (passwordHashed ? password : results.get(0).getPasswordHashMethod().doHash(password))
        )) {
            throw new IllegalStateException();
        }
        User user = results.get(0);

        /*
         * If hash method has changed in application settings and password parameter is not hashed, rehash user password
         */
        if (!user.getPasswordHashMethod().equals(getApplicationSettings().getDefaultHashImplementation()) && !passwordHashed) {
            user.setPasswordHashMethod(getApplicationSettings().getDefaultHashImplementation());
            user.setPassword(user.getPasswordHashMethod().doHash(password));
            getSessionEntityManager().persist(user);
        }

        setSessionUser(user);
        return fillUserSettings(new User(user));
    }

    @Transactional
    @Override
    public User login(String login, String password) {
        return this.login(login, password, false);
    }

    @RequireUser
    @Override
    public boolean logout() {
        setSessionUser(null);
        return true;
    }

    @Transactional
    @Override
    public User register(String login, String password) {
        if (getApplicationSettings().getRegistrationEnabled()) {
            TypedQuery<User> query = getSessionEntityManager().createQuery(
                    "SELECT x FROM User x WHERE x.login = :login", User.class);
            query.setParameter("login", login);
            List<User> results = query.getResultList();
            if (results.isEmpty()) {
                    User user = new User();
                    user.setLogin(login);
                    user.setPasswordHashMethod(getApplicationSettings().getDefaultHashImplementation());
                    user.setPassword(user.getPasswordHashMethod().doHash(password));
                    user.setManager(Boolean.TRUE); // registered users are always managers
                    user.setUserSettings(new UserSettings());
                    getSessionEntityManager().persist(user);
                    getSessionEntityManager().persist(UIStateEntry.createDefaultArchiveGridStateEntry(user));
                    setSessionUser(user);
                    return fillUserSettings(new User(user));
            }
            else
            {
                throw new IllegalStateException();
            }
        } else {
            throw new SecurityException();
        }
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @Override
    public List<User> getUsers() {
        User currentUser = getSessionUser();
        List<User> users = new LinkedList<User>();
        if (currentUser.getAdmin()) {
            users.addAll(getSessionEntityManager().createQuery("SELECT x FROM User x", User.class).getResultList());
        } else {
            users.addAll(currentUser.getAllManagedUsers());
        }
        for (User user : users) {
            fillUserSettings(user);
        }
        return users;
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public User addUser(User user) {
        User currentUser = getSessionUser();
        if (user.getLogin() == null || user.getLogin().isEmpty() ||
            user.getPassword() == null || user.getPassword().isEmpty()) {
            throw new IllegalArgumentException();
        }
        String login = user.getLogin();
        TypedQuery<User> query = getSessionEntityManager().createQuery("SELECT x FROM User x WHERE x.login = :login", User.class);
        query.setParameter("login", login);
        List<User> results = query.getResultList();

        if (results.isEmpty()) {
            if (!currentUser.getAdmin()) {
                user.setAdmin(false);
            }
            user.setManagedBy(currentUser);
            user.setPasswordHashMethod(getApplicationSettings().getDefaultHashImplementation());
            user.setPassword(user.getPasswordHashMethod().doHash(user.getPassword()));
            if (user.getUserSettings() == null) {
                user.setUserSettings(new UserSettings());
            }
            user.setNotificationEvents(user.getTransferNotificationEvents());
            getSessionEntityManager().persist(user);
            getSessionEntityManager().persist(UIStateEntry.createDefaultArchiveGridStateEntry(user));
            return fillUserSettings(user);
        } else {
            throw new IllegalStateException();
        }
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @Override
    public User updateUser(User user) {
        User currentUser = getSessionUser();
        if (user.getLogin().isEmpty() || user.getPassword().isEmpty()) {
            throw new IllegalArgumentException();
        }
        if (currentUser.getAdmin() || (currentUser.getId() == user.getId() && !user.getAdmin())) {
            EntityManager entityManager = getSessionEntityManager();
            // TODO: better solution?
            if (currentUser.getId() == user.getId()) {
                currentUser.setLogin(user.getLogin());
                // Password is different or hash method has changed since login
                if (!currentUser.getPassword().equals(user.getPassword()) || currentUser.getPasswordHashMethod().equals(PasswordHashMethod.PLAIN) && !getApplicationSettings().getDefaultHashImplementation().equals(PasswordHashMethod.PLAIN)) {
                    currentUser.setPasswordHashMethod(getApplicationSettings().getDefaultHashImplementation());
                    currentUser.setPassword(currentUser.getPasswordHashMethod().doHash(user.getPassword()));
                }
                currentUser.setUserSettings(user.getUserSettings());
                currentUser.setAdmin(user.getAdmin());
                currentUser.setManager(user.getManager());
                currentUser.setEmail(user.getEmail());
                currentUser.setNotificationEvents(user.getTransferNotificationEvents());
                entityManager.merge(currentUser);
                user = currentUser;
            } else {
                // update password
                if (currentUser.getAdmin() || currentUser.getManager()) {
                    User existingUser = entityManager.find(User.class, user.getId());
                    // Checks if password has changed or default hash method not equal to current user hash method
                    if (!existingUser.getPassword().equals(user.getPassword()) && !existingUser.getPassword().equals(existingUser.getPasswordHashMethod().doHash(user.getPassword())) || !existingUser.getPasswordHashMethod().equals(getApplicationSettings().getDefaultHashImplementation())) {
                        existingUser.setPasswordHashMethod(getApplicationSettings().getDefaultHashImplementation());
                        existingUser.setPassword(existingUser.getPasswordHashMethod().doHash(user.getPassword()));
                    }
                    entityManager.merge(existingUser);
                } else {
                    throw new SecurityException();
                }
            }

            return fillUserSettings(new User(user));
        } else {
            throw new SecurityException();
        }
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public User removeUser(User user) {
        EntityManager entityManager = getSessionEntityManager();
        user = entityManager.find(User.class, user.getId());
        // Don't allow user to delete himself
        if (user.equals(getSessionUser())) {
            throw new IllegalArgumentException();
        }
        // Allow manager to remove users only managed by himself
        if (!getSessionUser().getAdmin() && !getSessionUser().getAllManagedUsers().contains(user)) {
            throw new SecurityException();
        }
        entityManager.createQuery("DELETE FROM UIStateEntry s WHERE s.user=:user").setParameter("user", user).executeUpdate();
        for (Device device : user.getDevices()) {
            device.getUsers().remove(user);
        }
        for (GeoFence geoFence : user.getGeoFences()) {
            geoFence.getUsers().remove(user);
        }
        entityManager.remove(user);
        return fillUserSettings(user);
    }

    @Transactional
    @RequireUser
    @Override
    public List<Device> getDevices() {
        User user = getSessionUser();
        if (user.getAdmin()) {
            return getSessionEntityManager().createQuery("SELECT x FROM Device x LEFT JOIN FETCH x.latestPosition").getResultList();
        }
        return user.getAllAvailableDevices();
    }

    @Transactional
    @RequireUser
    @ManagesDevices
    @RequireWrite
    @Override
    public Device addDevice(Device device) throws TraccarException {
        if (device.getName() == null || device.getName().trim().isEmpty() ||
            device.getUniqueId() == null || device.getUniqueId().isEmpty()) {
            throw new ValidationException();
        }

        EntityManager entityManager = getSessionEntityManager();
        TypedQuery<Device> query = entityManager.createQuery("SELECT x FROM Device x WHERE x.uniqueId = :id", Device.class);
        query.setParameter("id", device.getUniqueId());
        List<Device> results = query.getResultList();

        User user = getSessionUser();

        if (results.isEmpty()) {
            device.setUsers(new HashSet<User>(1));
            device.getUsers().add(user);
            entityManager.persist(device);
            return device;
        } else {
            throw new DeviceExistsException();
        }
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @ManagesDevices
    @Override
    public Device updateDevice(Device device) throws TraccarException {
        if (device.getName() == null || device.getName().trim().isEmpty() ||
            device.getUniqueId() == null || device.getUniqueId().isEmpty()) {
            throw new ValidationException();
        }

        EntityManager entityManager = getSessionEntityManager();
        TypedQuery<Device> query = entityManager.createQuery("SELECT x FROM Device x WHERE x.uniqueId = :id AND x.id <> :primary_id", Device.class);
        query.setParameter("primary_id", device.getId());
        query.setParameter("id", device.getUniqueId());
        List<Device> results = query.getResultList();

        if (results.isEmpty()) {
            Device tmp_device = entityManager.find(Device.class, device.getId());
            tmp_device.setName(device.getName());
            tmp_device.setUniqueId(device.getUniqueId());
            tmp_device.setTimeout(device.getTimeout());
            tmp_device.setIdleSpeedThreshold(device.getIdleSpeedThreshold());
            tmp_device.setIconType(device.getIconType());
            return tmp_device;
        } else {
            throw new DeviceExistsException();
        }
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @ManagesDevices
    @Override
    public Device removeDevice(Device device) {
        EntityManager entityManager = getSessionEntityManager();
        User user = getSessionUser();
        device = entityManager.find(Device.class, device.getId());
        if (user.getAdmin() || user.getManager()) {
            device.getUsers().removeAll(getUsers());
        }
        device.getUsers().remove(user);
        /**
         * Remove device only if there is no more associated users in DB
         */
        if (device.getUsers().isEmpty()) {
            device.setLatestPosition(null);
            entityManager.flush();

            Query query = entityManager.createQuery("DELETE FROM DeviceEvent x WHERE x.device = :device");
            query.setParameter("device", device);
            query.executeUpdate();

            query = entityManager.createQuery("DELETE FROM Position x WHERE x.device = :device");
            query.setParameter("device", device);
            query.executeUpdate();

            entityManager.remove(device);
        }
        return device;
    }

    @Transactional
    @RequireUser
    @Override
    public List<Position> getPositions(Device device, Date from, Date to, boolean filter) {
        EntityManager entityManager = getSessionEntityManager();
        UserSettings filters = getSessionUser().getUserSettings();

        List<Position> positions = new LinkedList<Position>();
        String queryString = "SELECT x FROM Position x WHERE x.device = :device AND x.time BETWEEN :from AND :to";

        if (filter) {
            if (filters.isHideZeroCoordinates()) {
                queryString += " AND (x.latitude != 0 OR x.longitude != 0)";
            }
            if (filters.isHideInvalidLocations()) {
                queryString += " AND x.valid = TRUE";
            }
            if (filters.getSpeedModifier() != null && filters.getSpeedForFilter() != null) {
                queryString += " AND x.speed " + filters.getSpeedModifier() + " :speed";
            }
        }

        TypedQuery<Position> query = entityManager.createQuery(queryString, Position.class);
        query.setParameter("device", device);
        query.setParameter("from", from);
        query.setParameter("to", to);

        if (filter) {
            if (filters.getSpeedModifier() != null && filters.getSpeedForFilter() != null) {
                query.setParameter("speed", filters.getSpeedUnit().toKnots(filters.getSpeedForFilter()));
            }
        }

        List<Position> queryResult = query.getResultList();

        for (int i = 0; i < queryResult.size(); i++) {
            boolean add = true;
            if (i > 0) {
                Position positionA = queryResult.get(i - 1);
                Position positionB = queryResult.get(i);

                positionB.setDistance(GeoFenceCalculator.getDistance(positionA.getLongitude(), positionA.getLatitude(), positionB.getLongitude(), positionB.getLatitude()));

                if (filter && filters.isHideDuplicates()) {
                    add = !positionA.getTime().equals(positionB.getTime());
                }
                if (add && filter && filters.getMinDistance() != null) {
                    add = positionB.getDistance() >= filters.getMinDistance();
                }
            }
            if (add) positions.add(queryResult.get(i));
        }
        return positions;
    }

    @RequireUser
    @Transactional
    @Override
    public List<Position> getLatestPositions() {
        List<Position> positions = new LinkedList<Position>();
        List<Device> devices = getDevices();
        List<GeoFence> geoFences = getGeoFences(false);
        GeoFenceCalculator geoFenceCalculator = new GeoFenceCalculator(getGeoFences());
        if (devices != null && !devices.isEmpty()) {
            for (Device device : devices) {
                if (device.getLatestPosition() != null) {
                    Position position = device.getLatestPosition();
                    // calculate geo-fences
                    for (GeoFence geoFence : geoFences) {
                        if (geoFenceCalculator.contains(geoFence, position)) {
                            if (position.getGeoFences() == null) {
                                position.setGeoFences(new LinkedList<GeoFence>());
                            }
                            position.getGeoFences().add(geoFence);
                        }
                    }

                    positions.add(position);
                }
            }
        }
        return positions;
    }

    @RequireUser
    @Transactional
    @Override
    public List<Position> getLatestNonIdlePositions() {
        List<Position> positions = new LinkedList<Position>();
        List<Device> devices = getDevices();
        if (devices != null && !devices.isEmpty()) {
            EntityManager entityManager = getSessionEntityManager();

            for (Device device : devices) {
                List<Position> position = entityManager.createQuery("SELECT p FROM Position p WHERE p.device = :device AND p.speed > 0 ORDER BY time DESC", Position.class)
                        .setParameter("device", device)
                        .setMaxResults(1)
                        .getResultList();

                if (position.isEmpty()) {
                    position = entityManager.createQuery("SELECT p FROM Position p WHERE p.device = :device ORDER BY time ASC", Position.class)
                        .setParameter("device", device)
                        .setMaxResults(1)
                        .getResultList();
                }

                if (!position.isEmpty()) {
                    positions.add(position.get(0));
                }
            }
        }
        return positions;
    }

    @Transactional
    @Override
    public ApplicationSettings getApplicationSettings() {
        ApplicationSettings appSettings = applicationSettings.get();
        if (appSettings == null) {
            appSettings = new ApplicationSettings();
            entityManager.get().persist(appSettings);
        }
        return appSettings;
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN })
    @RequireWrite
    @Override
    public void updateApplicationSettings(ApplicationSettings applicationSettings) {
        getSessionEntityManager().merge(applicationSettings);
        eventService.applicationSettingsChanged();
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN })
    @Override
    public String getTrackerServerLog(short sizeKB) {
        File workingFolder = new File(System.getProperty("user.dir"));
        File logFile1 = new File(workingFolder, "logs" + File.separatorChar + "tracker-server.log");
        File logFile2 = new File(workingFolder.getParentFile(), "logs" + File.separatorChar + "tracker-server.log");
        File logFile3 = new File(workingFolder, "tracker-server.log");

        File logFile = logFile1.exists() ? logFile1 :
                logFile2.exists() ? logFile2 :
                        logFile3.exists() ? logFile3 : null;

        if (logFile != null) {
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(logFile, "r");
                int length = 0;
                if (raf.length() > Integer.MAX_VALUE) {
                    length = Integer.MAX_VALUE;
                } else {
                    length = (int) raf.length();
                }
                /**
                 * Read last 5 megabytes from file
                 */
                raf.seek(Math.max(0, raf.length() - sizeKB * 1024));
                byte[] result = new byte[Math.min(length, sizeKB * 1024)];
                raf.read(result);
                return new String(result);
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                try {
                    raf.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }

        return ("Tracker server log is not available. Looked at " + logFile1.getAbsolutePath() +
                ", " + logFile2.getAbsolutePath() +
                ", " + logFile3.getAbsolutePath());
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void saveRoles(List<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        EntityManager entityManager = getSessionEntityManager();
        User currentUser = getSessionUser();
        for (User _user : users) {
            User user = entityManager.find(User.class, _user.getId());
            if (currentUser.getAdmin()) {
                user.setAdmin(_user.getAdmin());
            }
            user.setManager(_user.getManager());
            user.setReadOnly(_user.getReadOnly());
        }
    }

    @Transactional
    @RequireUser
    @Override
    public Map<User, Boolean> getDeviceShare(Device device) {
        device = getSessionEntityManager().find(Device.class, device.getId());
        List<User> users = getUsers();
        Map<User, Boolean> result = new HashMap<User, Boolean>(users.size());
        for (User user : users) {
            result.put(fillUserSettings(user), device.getUsers().contains(user));
        }
        return result;
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void saveDeviceShare(Device device, Map<User, Boolean> share) {
        EntityManager entityManager = getSessionEntityManager();
        device = entityManager.find(Device.class, device.getId());

        for (User user : getUsers()) {
            Boolean shared = share.get(user);
            if (shared == null) continue;
            if (shared.booleanValue()) {
                device.getUsers().add(user);
            } else {
                device.getUsers().remove(user);
            }
            entityManager.merge(user);
        }
    }

    private User fillUserSettings(User user) {
        if (user.getUserSettings() instanceof HibernateProxy) {
            UserSettings settings = (UserSettings) ((HibernateProxy) user.getUserSettings()).getHibernateLazyInitializer().getImplementation();
            user.setUserSettings(settings);
        }
        return user;
    }

    @Transactional
    @RequireUser
    @Override
    public List<GeoFence> getGeoFences() {
        return getGeoFences(true);
    }

    private List<GeoFence> getGeoFences(boolean includeTransferDevices) {
        User user = getSessionUser();
        Set<GeoFence> geoFences;
        if (user.getAdmin()) {
            geoFences = new HashSet<GeoFence>(getSessionEntityManager().createQuery("SELECT g FROM GeoFence g LEFT JOIN FETCH g.devices", GeoFence.class).getResultList());
        } else {
            geoFences = user.getAllAvailableGeoFences();
        }

        if (includeTransferDevices) {
            for (GeoFence geoFence : geoFences) {
                geoFence.setTransferDevices(new HashSet<Device>(geoFence.getDevices()));
            }
        }

        return new ArrayList<GeoFence>(geoFences);
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @Override
    public GeoFence addGeoFence(GeoFence geoFence) throws TraccarException {
        if (geoFence.getName() == null || geoFence.getName().trim().isEmpty()) {
            throw new ValidationException();
        }

        geoFence.setUsers(new HashSet<User>());
        geoFence.getUsers().add(getSessionUser());
        geoFence.setDevices(geoFence.getTransferDevices());
        getSessionEntityManager().persist(geoFence);

        return geoFence;
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @Override
    public GeoFence updateGeoFence(GeoFence updatedGeoFence) throws TraccarException {
        if (updatedGeoFence.getName() == null || updatedGeoFence.getName().trim().isEmpty()) {
            throw new ValidationException();
        }

        GeoFence geoFence = getSessionEntityManager().find(GeoFence.class, updatedGeoFence.getId());
        geoFence.copyFrom(updatedGeoFence);

        // used to check access to the device
        List<Device> devices = getDevices();

        // process devices
        for (Iterator<Device> it = geoFence.getDevices().iterator(); it.hasNext(); ) {
            Device next = it.next();
            if (!updatedGeoFence.getTransferDevices().contains(next) && devices.contains(next)) {
                it.remove();
            }
        }
        updatedGeoFence.getTransferDevices().retainAll(devices);
        geoFence.getDevices().addAll(updatedGeoFence.getTransferDevices());

        return geoFence;
    }

    @Transactional
    @RequireUser
    @RequireWrite
    @Override
    public GeoFence removeGeoFence(GeoFence geoFence) {
        User user = getSessionUser();
        geoFence = getSessionEntityManager().find(GeoFence.class, geoFence.getId());
        if (user.getAdmin() || user.getManager()) {
            geoFence.getUsers().removeAll(getUsers());
        }
        geoFence.getUsers().remove(user);
        if (geoFence.getUsers().isEmpty()) {
            Query query = entityManager.get().createQuery("DELETE FROM DeviceEvent x WHERE x.geoFence = :geoFence");
            query.setParameter("geoFence", geoFence);
            query.executeUpdate();

            getSessionEntityManager().remove(geoFence);
        }
        return geoFence;
    }

    @Transactional
    @RequireUser
    @Override
    public Map<User, Boolean> getGeoFenceShare(GeoFence geoFence) {
        geoFence = getSessionEntityManager().find(GeoFence.class, geoFence.getId());
        List<User> users = getUsers();
        Map<User, Boolean> result = new HashMap<User, Boolean>(users.size());
        for (User user : users) {
            result.put(fillUserSettings(user), geoFence.getUsers().contains(user));
        }
        return result;
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void saveGeoFenceShare(GeoFence geoFence, Map<User, Boolean> share) {
        EntityManager entityManager = getSessionEntityManager();
        geoFence = entityManager.find(GeoFence.class, geoFence.getId());

        for (User user : getUsers()) {
            Boolean shared = share.get(user);
            if (shared == null) continue;
            if (shared) {
                geoFence.getUsers().add(user);
            } else {
                geoFence.getUsers().remove(user);
            }
            entityManager.merge(user);
        }
    }
}
