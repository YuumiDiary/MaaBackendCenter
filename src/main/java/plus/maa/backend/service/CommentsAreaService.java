package plus.maa.backend.service;


import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import plus.maa.backend.common.utils.converter.CommentConverter;
import plus.maa.backend.controller.request.CommentsAddDTO;
import plus.maa.backend.controller.request.CommentsQueriesDTO;
import plus.maa.backend.controller.request.CommentsRatingDTO;
import plus.maa.backend.controller.response.CommentsAreaInfo;
import plus.maa.backend.controller.response.CommentsInfo;
import plus.maa.backend.controller.response.SubCommentsInfo;
import plus.maa.backend.repository.CommentsAreaRepository;
import plus.maa.backend.repository.CopilotRepository;
import plus.maa.backend.repository.TableLogicDelete;
import plus.maa.backend.repository.UserRepository;
import plus.maa.backend.repository.entity.CommentsArea;
import plus.maa.backend.repository.entity.CopilotRating;
import plus.maa.backend.repository.entity.MaaUser;
import plus.maa.backend.service.model.LoginUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * @author LoMu
 * Date  2023-02-17 15:00
 */

@Service
@RequiredArgsConstructor
public class CommentsAreaService {
    private final CommentsAreaRepository commentsAreaRepository;

    private final CopilotRepository copilotRepository;

    private final UserRepository userRepository;

    private final TableLogicDelete tableLogicDelete;


    /**
     * 评论
     * 每个评论都有一个uuid加持
     *
     * @param loginUser      登录用户
     * @param commentsAddDTO CommentsRequest
     */
    public void addComments(LoginUser loginUser, CommentsAddDTO commentsAddDTO) {
        long copilotId = Long.parseLong(commentsAddDTO.getCopilotId());
        MaaUser maaUser = loginUser.getMaaUser();
        String message = commentsAddDTO.getMessage();

        Assert.isTrue(StringUtils.isNotBlank(message), "评论不可为空");
        Assert.isTrue(copilotRepository.existsCopilotsByCopilotId(copilotId), "作业表不存在");


        String fromCommentsId = null;
        String mainCommentsId = null;

        if (StringUtils.isNoneBlank(commentsAddDTO.getFromCommentId())) {

            Optional<CommentsArea> commentsAreaOptional = commentsAreaRepository.findById(commentsAddDTO.getFromCommentId());
            Assert.isTrue(commentsAreaOptional.isPresent(), "回复的评论不存在");
            CommentsArea rawCommentsArea = commentsAreaOptional.get();

            //判断其回复的评论是主评论 还是子评论
            mainCommentsId = StringUtils
                    .isNoneBlank(rawCommentsArea.getMainCommentId()) ?
                    rawCommentsArea.getMainCommentId() : rawCommentsArea.getId();

            fromCommentsId = StringUtils
                    .isNoneBlank(rawCommentsArea.getId()) ?
                    rawCommentsArea.getId() : null;

        }

        //创建评论表
        CommentsArea commentsArea = new CommentsArea();
        commentsArea.setCopilotId(copilotId)
                .setUploaderId(maaUser.getUserId())
                .setFromCommentId(fromCommentsId)
                .setMainCommentId(mainCommentsId)
                .setMessage(message);
        commentsAreaRepository.insert(commentsArea);

    }


    public void deleteComments(LoginUser loginUser, String commentsId) {
        CommentsArea commentsArea = findCommentsById(commentsId);
        verifyOwner(loginUser, commentsArea.getUploaderId());

        tableLogicDelete.deleteCommentsId(commentsId);
    }


    /**
     * 为评论进行点赞
     *
     * @param loginUser         登录用户
     * @param commentsRatingDTO CommentsRatingDTO
     */
    public void rates(LoginUser loginUser, CommentsRatingDTO commentsRatingDTO) {
        String userId = loginUser.getMaaUser().getUserId();
        String rating = commentsRatingDTO.getRating();
        boolean existRatingUser = false;

        CommentsArea commentsArea = findCommentsById(commentsRatingDTO.getCommentId());
        List<CopilotRating.RatingUser> ratingUserList = commentsArea.getRatingUser();

        //判断是否存在 如果已存在则修改评分
        for (CopilotRating.RatingUser ratingUser : ratingUserList) {
            if (Objects.equals(userId, ratingUser.getUserId())) {
                ratingUser.setRating(rating);
                existRatingUser = true;
            }
        }

        //不存在 创建一个用户评分
        if (!existRatingUser) {
            CopilotRating.RatingUser ratingUser = new CopilotRating.RatingUser(userId, rating);
            ratingUserList.add(ratingUser);
        }

        long likeCount = ratingUserList.stream()
                .filter(ratingUser ->
                        Objects.equals(ratingUser.getRating(), "Like"))
                .count();
        commentsArea.setRatingUser(ratingUserList);
        commentsArea.setLikeCount(likeCount);


        commentsAreaRepository.save(commentsArea);
    }


    /**
     * 查询
     *
     * @param request CommentsQueriesDTO
     * @return CommentsAreaInfo
     */
    public CommentsAreaInfo queriesCommentsArea(CommentsQueriesDTO request) {
        Sort.Order sortOrder = new Sort.Order(
                request.isDesc() ? Sort.Direction.DESC : Sort.Direction.ASC,
                Optional.ofNullable(request.getOrderBy())
                        .filter(StringUtils::isNotBlank)
                        .map(ob -> switch (ob) {
                            case "hot" -> "likeCount";
                            case "id" -> "uploadTime";
                            default -> request.getOrderBy();
                        }).orElse("likeCount"));

        int page = request.getPage() > 0 ? request.getPage() : 1;
        int limit = request.getLimit() > 0 ? request.getLimit() : 10;

        boolean hasNext = false;
        Pageable pageable = PageRequest.of(page - 1, limit, Sort.by(sortOrder));


        Page<CommentsArea> rawCommentsAreaList = commentsAreaRepository.findByCopilotIdAndDeleteAndMainCommentIdExists(request.getCopilotId(), false, false, pageable);
        long count = rawCommentsAreaList.getTotalElements();

        int pageNumber = rawCommentsAreaList.getTotalPages();

        // 判断是否存在下一页
        if (count - (long) page * limit > 0) {
            hasNext = true;
        }


        List<CommentsInfo> commentsInfoList = new ArrayList<>();

        //获取主评论
        rawCommentsAreaList.stream()
                .filter(c ->
                        StringUtils.isBlank(c.getMainCommentId()))
                .forEach(mainComment -> {
                    List<CommentsArea> commentsAreas = commentsAreaRepository.findByMainCommentIdAndDeleteIsFalse(mainComment.getId());
                    List<SubCommentsInfo> subCommentsInfoList = commentsAreas.stream()
                            //将其转换为subCommentsInfo 并封装实时查询用户名
                            .map(c -> {
                                SubCommentsInfo subCommentsInfo = CommentConverter.INSTANCE.toSubCommentsInfo(c);
                                subCommentsInfo.setReplyTo(findCommentUploaderName(c.getFromCommentId()))
                                        .setUploader(findUsername(c.getUploaderId()));
                                return subCommentsInfo;
                            })
                            .toList();

                    CommentsInfo commentsInfo = CommentConverter.INSTANCE.toCommentsInfo(mainComment);
                    commentsInfo.setUploader(findUsername(commentsInfo.getUploaderId()));
                    commentsInfo.setSubCommentsInfos(subCommentsInfoList);
                    commentsInfoList.add(commentsInfo);
                });


        return new CommentsAreaInfo().setHasNext(hasNext)
                .setPage(pageNumber)
                .setTotal(count)
                .setData(commentsInfoList);
    }


    private void verifyOwner(LoginUser user, String uploaderId) {
        Assert.isTrue(Objects.equals(user.getMaaUser().getUserId(), uploaderId), "您无法删除不属于您的评论");
    }

    /**
     * @param id 用户id
     * @return 用户名
     */

    private String findUsername(String id) {
        Optional<MaaUser> byId = userRepository.findById(id);
        return byId.isPresent() ? byId.get().getUserName() : "账号已注销";
    }

    /**
     * @param id 评论id
     * @return 评论用户名
     */
    private String findCommentUploaderName(String id) {
        Optional<CommentsArea> byId = commentsAreaRepository.findById(id);
        return byId.map(commentsArea -> findUsername(commentsArea.getUploaderId())).orElse("遁入虚空的评论");
    }

    private CommentsArea findCommentsById(String commentsId) {
        Optional<CommentsArea> commentsArea = commentsAreaRepository.findById(commentsId);
        Assert.isTrue(commentsArea.isPresent(), "评论不存在");
        return commentsArea.get();
    }


}
