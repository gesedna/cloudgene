package cloudgene.mapred.resources.data;

import net.sf.json.JSONArray;

import org.jets3t.service.ServiceException;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;

import cloudgene.mapred.core.User;
import cloudgene.mapred.util.BaseResource;
import cloudgene.mapred.util.BucketItem;
import cloudgene.mapred.util.BucketTree;

public class GetMyBuckets extends BaseResource {

	@Get
	public Representation get() {

		User user = getUser(getRequest());

		StringRepresentation representation = null;

		if (user != null) {

			BucketItem[] items = null;
			try {

				items = BucketTree.getBucketTree(user.getAwsKey(),
						user.getAwsSecretKey(), "");

				if (items == null) {

					representation = new StringRepresentation("");
					getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
					getResponse().setEntity(representation);
					return representation;

				}

			} catch (ServiceException e) {

				representation = new StringRepresentation("");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(representation);
				return representation;

			}
			JSONArray jsonArray = JSONArray.fromObject(items);

			representation = new StringRepresentation(jsonArray.toString());
			getResponse().setStatus(Status.SUCCESS_OK);
			getResponse().setEntity(representation);

			return representation;

		} else {

			representation = new StringRepresentation("");
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			getResponse().setEntity(representation);
			return representation;
		}

	}

}
