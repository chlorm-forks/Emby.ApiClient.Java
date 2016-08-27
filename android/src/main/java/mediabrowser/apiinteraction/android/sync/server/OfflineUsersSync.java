package mediabrowser.apiinteraction.android.sync.server;

import mediabrowser.apiinteraction.ApiClient;
import mediabrowser.apiinteraction.EmptyResponse;
import mediabrowser.apiinteraction.Response;
import mediabrowser.apiinteraction.ResponseStreamInfo;
import mediabrowser.apiinteraction.sync.data.ILocalAssetManager;
import mediabrowser.apiinteraction.tasks.CancellationToken;
import mediabrowser.model.apiclient.ServerInfo;
import mediabrowser.model.apiclient.ServerUserInfo;
import mediabrowser.model.dto.ImageOptions;
import mediabrowser.model.dto.UserDto;
import mediabrowser.model.entities.ImageType;
import mediabrowser.model.logging.ILogger;
import mediabrowser.model.net.HttpException;

import java.io.InputStream;
import java.util.ArrayList;

public class OfflineUsersSync {

    private ILogger logger;
    private ILocalAssetManager localAssetManager;

    public OfflineUsersSync(ILogger logger, ILocalAssetManager localAssetManager) {
        this.logger = logger;
        this.localAssetManager = localAssetManager;
    }

    public void UpdateOfflineUsers(ServerInfo server, final ApiClient apiClient, CancellationToken cancellationToken, final EmptyResponse response){

        Object[] users = server.getUsers().toArray();

        final int count = users.length;

        if (count == 0){
            response.onResponse();
        }

        final ArrayList<Integer> doneList = new ArrayList<Integer>();

        for (Object objUser : users){

            ServerUserInfo user = (ServerUserInfo)objUser;

            SaveOfflineUser(user, apiClient, new EmptyResponse(){

                private void onAny() {

                    synchronized (doneList) {

                        doneList.add(0);

                        if (doneList.size() >= count) {
                            response.onResponse();
                        }
                    }
                }

                @Override
                public void onResponse() {
                    onAny();
                }

                @Override
                public void onError(Exception ex) {
                    logger.ErrorException("Error saving offline user", ex);
                    onAny();
                }
            });
        }
    }

    private void SaveOfflineUser(final ServerUserInfo user, final ApiClient apiClient, final EmptyResponse response){

        final String userId = user.getId();

        apiClient.GetOfflineUser(userId, new Response<UserDto>(response){

            @Override
            public void onResponse(UserDto user) {

                localAssetManager.saveOfflineUser(user);
                UpdateUserImage(user, apiClient, response);
            }

            @Override
            public void onError(Exception ex) {

                logger.ErrorException("Error getting user info", ex);

                if (ex instanceof HttpException){
                    HttpException httpException = (HttpException)ex;

                    if (httpException.getStatusCode() != null && httpException.getStatusCode() == 401){
                        localAssetManager.deleteOfflineUser(userId);
                    }
                }
                response.onResponse();
            }
        });
    }

    private void UpdateUserImage(final UserDto user, ApiClient apiClient, final EmptyResponse response){

        if (user.getHasPrimaryImage())
        {
            boolean hasLocalImage = localAssetManager.hasImage(user);
            logger.Info("localAssetManager has image for user %s ? %s", user.getName(), hasLocalImage);
            if (hasLocalImage){

                response.onResponse();
            }
            else
            {
                ImageOptions imageOptions = new ImageOptions();
                imageOptions.setImageType(ImageType.Primary);
                String imageUrl = apiClient.GetUserImageUrl(user, imageOptions);

                logger.Info("Getting image stream from %s", imageUrl);

                apiClient.getResponseStream(imageUrl, new Response<ResponseStreamInfo>(response) {

                    @Override
                    public void onResponse(ResponseStreamInfo responseStreamInfo) {

                        try (InputStream alias = responseStreamInfo.Stream) {

                            logger.Info("Got image stream, calling localAssetManager.saveImage");
                            localAssetManager.saveImage(user, alias);
                            logger.Info("Image save complete");
                            response.onResponse();

                        } catch (Exception ex) {
                            response.onError(ex);
                        }

                    }

                });
            }
        }
        else
        {
            try {
                localAssetManager.deleteImage(user);
            }
            catch (Exception ex){
                logger.ErrorException("Error deleting image", ex);
            }

            response.onResponse();
        }
    }
}
