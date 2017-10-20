#!/usr/bin/env bash

PORT=${1:-8888}
# update the "further_details_access_question" entry in the DT
curl -v -H "Content-Type: application/json" -X PUT http://localhost:${PORT}/decisiontable/further_details_access_question -d '{
	"queries": ["cannot access account", "problem access account", "unable to access to my account", "completely forgot my password"]
}' 

